use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant},
};

use eyre::{eyre, Report};
use dashmap::DashMap;
use serenity::{
    all::{
        ChannelId, Context, CreateInteractionResponse, CreateInteractionResponseMessage, EventHandler,
        GatewayIntents, Guild, Http, Interaction, Ready, UserId, VoiceState,
    },
    Client,
};
use songbird::SerenityInit;
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

use crate::runtime::RUNTIME;

use super::{Click, State};

impl super::DiscordBot {
    #[tracing::instrument(skip(self), fields(self.vc_id = %self.vc_id))]
    pub fn log_in(&mut self) -> Result<(), Report> {
        let mut state_lock = self.state.write();

        if !matches!(*state_lock, State::NotLoggedIn) {
            info!("Already logged in or currently logging in");
            return Ok(());
        }

        *state_lock = State::LoggingIn;

        let (tx, mut rx) = mpsc::channel(1);

        let token = self.token.clone();
        let songbird = self.songbird.clone();
        let handler = Handler {
            log_in_tx: tx.clone(),
            vc_id: self.vc_id,
            voice_members: self.voice_members.clone(),
            guild_seen: self.guild_seen.clone(),
            clicks_tx: self.clicks_tx.clone(),
        };
        let mut client_task = self.client_task.lock();
        // While this should never happen, it's better to catch it than leave it running
        if let Some(client_task) = &*client_task {
            info!("Aborting previous client task");
            client_task.abort();
            // Wait for it to finish or 20 seconds to pass
            let start = Instant::now();
            while !client_task.is_finished()
                && start.duration_since(Instant::now()) < Duration::from_secs(10)
            {
                info!("Sleeping for client task to finish");
                thread::sleep(Duration::from_millis(500));
            }
            if !client_task.is_finished() {
                warn!("Client task did not finish");
            }
        }
        *client_task = Some(
            RUNTIME
                .spawn(async move {
                    // GUILDS: the guild (with who's in voice) arrives right after logging in, for call requests.
                    let intents = GatewayIntents::GUILDS | GatewayIntents::GUILD_VOICE_STATES;

                    let mut client = match Client::builder(&token, intents)
                        .event_handler(handler)
                        .register_songbird_with(songbird)
                        .await
                    {
                        Ok(c) => c,
                        Err(e) => {
                            warn!(?e);
                            if let Err(e) = tx.send(Err(Report::new(e))).await {
                                debug!(?e, "log_in rx dropped");
                            }
                            return;
                        }
                    };

                    if let Err(e) = client.start().await {
                        warn!(?e);
                        if let Err(e) = tx.send(Err(Report::new(e))).await {
                            debug!(?e, "log_in rx dropped");
                        }
                    } else {
                        info!("Bot finished");
                    }
                })
                .abort_handle(),
        );

        match rx.blocking_recv() {
            Some(Ok(http)) => {
                *state_lock = State::LoggedIn { http };
                Ok(())
            }
            Some(Err(e)) => {
                *state_lock = State::NotLoggedIn;
                Err(e)
            }
            None => {
                *state_lock = State::NotLoggedIn;
                Err(eyre!("log_in tx dropped"))
            }
        }
    }
}

struct Handler {
    pub log_in_tx: mpsc::Sender<Result<Arc<Http>, Report>>,
    vc_id: ChannelId,
    voice_members: Arc<DashMap<UserId, bool>>,
    guild_seen: Arc<AtomicBool>,
    clicks_tx: flume::Sender<Click>,
}

#[serenity::async_trait]
impl EventHandler for Handler {
    async fn ready(&self, ctx: Context, _ready: Ready) {
        if let Err(e) = self.log_in_tx.send(Ok(ctx.http)).await {
            debug!(?e, "log_in rx dropped");
        }
    }

    async fn guild_create(&self, _ctx: Context, guild: Guild, _is_new: Option<bool>) {
        if !guild.channels.contains_key(&self.vc_id) {
            return;
        }
        self.voice_members.clear();
        for (user_id, state) in &guild.voice_states {
            if state.channel_id == Some(self.vc_id) {
                let bot = state
                    .member
                    .as_ref()
                    .or_else(|| guild.members.get(user_id))
                    .is_some_and(|m| m.user.bot);
                self.voice_members.insert(*user_id, bot);
            }
        }
        self.guild_seen.store(true, Ordering::SeqCst);
        debug!(people = self.voice_members.len(), "Got the guild of the voice channel");
    }

    async fn voice_state_update(&self, _ctx: Context, _old: Option<VoiceState>, new: VoiceState) {
        // Someone can only be in one voice channel at a time, so anything else means they left ours.
        if new.channel_id == Some(self.vc_id) {
            let bot = new.member.as_ref().is_some_and(|m| m.user.bot);
            self.voice_members.insert(new.user_id, bot);
        } else {
            self.voice_members.remove(&new.user_id);
        }
    }

    /// Buttons on call request messages. Only people in the voice channel get to answer; the Java side decides
    /// what the click means.
    async fn interaction_create(&self, ctx: Context, interaction: Interaction) {
        let Interaction::Component(click) = interaction else {
            return;
        };
        if click.channel_id != self.vc_id || !click.data.custom_id.starts_with("vcd:") {
            return;
        }
        let in_channel = self
            .voice_members
            .get(&click.user.id)
            .is_some_and(|bot| !*bot);
        let response = if in_channel {
            CreateInteractionResponse::Acknowledge
        } else {
            CreateInteractionResponse::Message(
                CreateInteractionResponseMessage::new()
                    .content("Only people in this voice channel can answer.")
                    .ephemeral(true),
            )
        };
        if let Err(e) = click.create_response(&ctx.http, response).await {
            warn!(?e, "Couldn't answer a button click");
        }
        if in_channel {
            let user_name = click
                .member
                .as_ref()
                .map(|m| m.display_name().to_owned())
                .unwrap_or_else(|| click.user.display_name().to_owned());
            if self
                .clicks_tx
                .try_send(Click {
                    custom_id: click.data.custom_id.clone(),
                    message_id: click.message.id.get(),
                    user_name,
                })
                .is_err()
            {
                warn!("Dropped a button click: nobody is reading them");
            }
        }
    }
}
