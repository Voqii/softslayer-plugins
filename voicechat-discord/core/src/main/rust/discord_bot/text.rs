use std::{
    sync::{atomic::Ordering, Arc},
    thread,
    time::{Duration, Instant},
};

use eyre::{eyre, Context as _, Report};
use serenity::all::{
    ButtonStyle, Channel, CreateActionRow, CreateAllowedMentions, CreateButton, CreateMessage, EditMessage,
    GuildId, Http, MessageId,
};

use crate::runtime::RUNTIME;

use super::State;

/// A button click from someone in the voice channel.
pub struct Click {
    pub custom_id: String,
    pub message_id: u64,
    /// Their server nickname, or their Discord name.
    pub user_name: String,
}

/// (custom id, label, style), style as Discord numbers it: 1 primary, 2 secondary, 3 success, 4 danger.
pub type Button = (String, String, u8);

fn action_rows(buttons: Vec<Button>) -> Vec<CreateActionRow> {
    if buttons.is_empty() {
        return vec![];
    }
    vec![CreateActionRow::Buttons(
        buttons
            .into_iter()
            .map(|(id, label, style)| {
                CreateButton::new(id).label(label).style(match style {
                    1 => ButtonStyle::Primary,
                    3 => ButtonStyle::Success,
                    4 => ButtonStyle::Danger,
                    _ => ButtonStyle::Secondary,
                })
            })
            .collect(),
    )]
}

/// Call request messages go in the voice channel's own text chat (same id as the voice channel).
impl super::DiscordBot {
    fn http(&self) -> Result<Arc<Http>, Report> {
        match &*self.state.read() {
            State::LoggedIn { http } | State::Started { http, .. } => Ok(http.clone()),
            _ => Err(eyre!("Bot is not logged in")),
        }
    }

    fn guild(&self, http: &Http) -> Result<GuildId, Report> {
        let mut cached = self.guild_id.lock();
        if let Some(id) = *cached {
            return Ok(id);
        }
        let id = match RUNTIME
            .block_on(http.get_channel(self.vc_id))
            .wrap_err("Couldn't get voice channel")?
        {
            Channel::Guild(c) => c.guild_id,
            _ => return Err(eyre!("The specified channel is not in a server.")),
        };
        *cached = Some(id);
        Ok(id)
    }

    /// People (not bots) in the voice channel. Right after logging in, waits a few seconds for Discord to send
    /// the guild, so a fresh login doesn't count an empty channel.
    pub fn humans_in_voice_channel(&self) -> usize {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !self.guild_seen.load(Ordering::SeqCst) && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(50));
        }
        self.voice_members.iter().filter(|m| !*m.value()).count()
    }

    pub fn post_message(&self, content: String, buttons: Vec<Button>) -> Result<u64, Report> {
        let http = self.http()?;
        let message = CreateMessage::new()
            .content(content)
            .allowed_mentions(CreateAllowedMentions::new())
            .components(action_rows(buttons));
        let sent = RUNTIME
            .block_on(self.vc_id.send_message(&http, message))
            .wrap_err("Couldn't post in the voice channel's chat")?;
        Ok(sent.id.get())
    }

    pub fn edit_message(&self, id: u64, content: String, buttons: Vec<Button>) -> Result<(), Report> {
        let http = self.http()?;
        let edit = EditMessage::new()
            .content(content)
            .allowed_mentions(CreateAllowedMentions::new())
            .components(action_rows(buttons));
        RUNTIME
            .block_on(self.vc_id.edit_message(&http, MessageId::new(id), edit))
            .wrap_err("Couldn't edit a message in the voice channel's chat")?;
        Ok(())
    }

    /// `None` puts the bot's own name back.
    pub fn set_nickname(&self, nickname: Option<String>) -> Result<(), Report> {
        let http = self.http()?;
        let guild = self.guild(&http)?;
        RUNTIME
            .block_on(guild.edit_nickname(&http, nickname.as_deref()))
            .wrap_err("Couldn't change the bot's nickname")?;
        Ok(())
    }

    pub fn next_click(&self, timeout: Duration) -> Option<Click> {
        self.clicks_rx.recv_timeout(timeout).ok()
    }
}
