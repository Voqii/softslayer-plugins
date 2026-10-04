package dev.voqii.cfddr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.momirealms.customfishing.api.BukkitCustomFishingPlugin;
import net.momirealms.customfishing.api.mechanic.fishing.CustomFishingHook;
import net.momirealms.customfishing.api.mechanic.game.AbstractGame;
import net.momirealms.customfishing.api.mechanic.game.AbstractGamingPlayer;
import net.momirealms.customfishing.api.mechanic.game.GameBasics;
import net.momirealms.customfishing.api.mechanic.game.GameSetting;
import net.momirealms.customfishing.api.mechanic.misc.value.MathValue;
import net.momirealms.customfishing.libraries.boostedyaml.block.implementation.Section;
import org.bukkit.Input;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * Dance-Dance-Revolution style fishing game. Arrows scroll right-to-left into a
 * target box; press the matching key as each one lands.
 *
 * Inputs ("controls"): wasd (default) = the movement keys, W up / A left / S down / D right, with
 * walking frozen for the duration; mouse = left click / sneak / F / right click; or both.
 *
 * Default visuals use only vanilla font glyphs. Every track cell is exactly 12px wide
 * (vanilla widths: '←'/'→' 8px, '↑'/'↓' 6px, space 4px, '·' 2px, bold adds 1px), so the
 * title width is constant and the track doesn't jitter. Keep that in mind if you override
 * the cells in config.
 */
public class DDRGame extends AbstractGame {

    static final int LEFT = 0, DOWN = 1, UP = 2, RIGHT = 3;
    private static final String[] DIR_NAMES = {"left", "down", "up", "right"};
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final NamespacedKey FREEZE_KEY = NamespacedKey.fromString("cfddr:freeze");

    // chart
    private final int notesMin, notesMax;
    private final int lanes;
    private final long approachMs, leadInMs;
    private final double maxGapMs, minGapMs, halfStepChance, longStepChance;
    private final long minHalfStepMs;
    // judgement
    private final long perfectMs, goodMs;
    private final int lives;
    private final boolean wrongArrowCostsLife;
    private final boolean pingCompensation;
    private final long maxPingCompMs;
    private final long phantomClickMs;
    // controls
    private final boolean wasdControls, mouseControls, freezeMovement;
    // display
    private final boolean trackInTitle;
    private final String[] cells = new String[4];
    private final String[] flashCells = new String[4];
    private final String emptyCell, targetEmptyCell;
    private final String bracketIdle, bracketPerfect, bracketGood, bracketMiss;
    private final String textPerfect, textGood, textMiss, textReady, textCleared, textFullCombo, textFailed;
    private final String statusFormat, lifeFull, lifeEmpty;
    private final String tip;
    private final String soundPerfect, soundGood, soundMiss;

    public DDRGame(String id, Section section) {
        super(id, GameBasics.builder()
                .difficulty(MathValue.auto(section.get("difficulty", "20~80"), true))
                .time(MathValue.auto(section.get("time", 30), true))
                .build());

        String notes = String.valueOf(section.get("arguments.notes", "8~14"));
        if (notes.contains("~")) {
            String[] split = notes.split("~");
            notesMin = Integer.parseInt(split[0].trim());
            notesMax = Integer.parseInt(split[1].trim());
        } else {
            notesMin = notesMax = Integer.parseInt(notes.trim());
        }
        lanes = Math.max(4, section.getInt("arguments.lanes", 9));
        approachMs = seconds(section.getDouble("arguments.approach-time", 1.4));
        leadInMs = seconds(section.getDouble("arguments.lead-in", 1.0));
        maxGapMs = section.getDouble("arguments.max-gap", 0.75) * 1000;
        minGapMs = section.getDouble("arguments.min-gap", 0.40) * 1000;
        halfStepChance = section.getDouble("arguments.half-step-chance", 0.15);
        longStepChance = section.getDouble("arguments.long-step-chance", 0.15);
        minHalfStepMs = seconds(section.getDouble("arguments.min-half-step", 0.22));
        perfectMs = seconds(section.getDouble("arguments.perfect-window", 0.09));
        goodMs = seconds(section.getDouble("arguments.good-window", 0.18));
        lives = section.getInt("arguments.lives", 4);
        wrongArrowCostsLife = section.getBoolean("arguments.wrong-arrow-costs-life", true);
        pingCompensation = section.getBoolean("arguments.ping-compensation", true);
        maxPingCompMs = seconds(section.getDouble("arguments.max-ping-compensation", 0.25));
        phantomClickMs = seconds(section.getDouble("arguments.phantom-click-filter", 0.1));

        String controls = section.getString("controls", "wasd").toLowerCase();
        wasdControls = !controls.equals("mouse");
        mouseControls = !controls.equals("wasd");
        freezeMovement = wasdControls && section.getBoolean("freeze-movement", true);

        trackInTitle = !"subtitle".equalsIgnoreCase(section.getString("display", "title"));
        String[] defaultColors = {"#c77dff", "#4cc9f0", "#80ed99", "#ff6b6b"};
        for (int d = 0; d < 4; d++) {
            String color = section.getString("arrow-color." + DIR_NAMES[d], defaultColors[d]);
            String template = section.getString("cells." + DIR_NAMES[d], defaultCell(d));
            cells[d] = template.replace("{color}", color);
            flashCells[d] = template.replace("{color}", section.getString("arrow-color.flash", "white"));
        }
        emptyCell = section.getString("cells.empty", "<dark_gray>·</dark_gray><b>  </b>");
        targetEmptyCell = section.getString("cells.target-empty", "   ");
        bracketIdle = section.getString("target.idle", "<gray>");
        bracketPerfect = section.getString("target.perfect", "<gold>");
        bracketGood = section.getString("target.good", "<green>");
        bracketMiss = section.getString("target.miss", "<red>");

        textPerfect = section.getString("text.perfect", "<gold><b>PERFECT</b>");
        textGood = section.getString("text.good", "<green><b>GOOD</b>");
        textMiss = section.getString("text.miss", "<red><b>MISS</b>");
        textReady = section.getString("text.ready", "<yellow>Get ready...");
        textCleared = section.getString("text.cleared", "<green><b>CLEARED!</b>");
        textFullCombo = section.getString("text.full-combo", "<gold><b>FULL COMBO!</b>");
        textFailed = section.getString("text.failed", "<red><b>It got away...</b>");
        statusFormat = section.getString("status", "{judgement}  <gray>combo <white>{combo}</white>  {lives}");
        lifeFull = section.getString("text.life", "<red>❤");
        lifeEmpty = section.getString("text.life-lost", "<dark_gray>❤");
        tip = section.getString("tip", wasdControls
                ? "<color:#c77dff>← <key:key.left></color>  <color:#4cc9f0>↓ <key:key.back></color>  " +
                  "<color:#80ed99>↑ <key:key.forward></color>  <color:#ff6b6b>→ <key:key.right></color>"
                : "<color:#c77dff>← <key:key.attack></color>  <color:#4cc9f0>↓ <key:key.sneak></color>  " +
                  "<color:#80ed99>↑ <key:key.swapOffhand></color>  <color:#ff6b6b>→ <key:key.use></color>");
        soundPerfect = section.getString("sound.perfect", "minecraft:block.note_block.bell");
        soundGood = section.getString("sound.good", "minecraft:block.note_block.hat");
        soundMiss = section.getString("sound.miss", "minecraft:block.note_block.bass");
    }

    private static long seconds(double s) {
        return Math.round(s * 1000);
    }

    // 12px per cell with vanilla glyphs: 8px arrow + 4px space, or 7px bold arrow + 5px bold space
    private static String defaultCell(int dir) {
        return switch (dir) {
            case LEFT -> "<{color}>←</{color}> ";
            case RIGHT -> "<{color}>→</{color}> ";
            case UP -> "<{color}><b>↑ </b></{color}>";
            default -> "<{color}><b>↓ </b></{color}>";
        };
    }

    @Override
    public BiFunction<CustomFishingHook, GameSetting, AbstractGamingPlayer> gamingPlayerProvider() {
        return DDRPlayer::new;
    }

    private static final class Note {
        final long time;
        final int dir;
        int state; // 0 pending, 1 hit, 2 missed

        Note(long time, int dir) {
            this.time = time;
            this.dir = dir;
        }
    }

    private enum Judgement { NONE, READY, PERFECT, GOOD, MISS }

    private final class DDRPlayer extends AbstractGamingPlayer implements DDRInputListener.InputSink {

        // not initialised in declarations: the super constructor schedules tick() before they would run
        private volatile boolean ready;
        private List<Note> notes;
        private int nextPending;
        private int combo, maxCombo, livesLeft, misses;
        private long lastRightClick;
        private Judgement judgement;
        private long judgementAt;
        private int flashDir;
        private long flashAt;
        private boolean finished;
        private Input lastInput;
        private boolean frozen;

        DDRPlayer(CustomFishingHook hook, GameSetting setting) {
            super(hook, setting);
            long now = System.currentTimeMillis();
            double diff = Math.max(0, Math.min(100, setting.difficulty())) / 100.0;
            int count = (int) Math.round(notesMin + (notesMax - notesMin) * diff);
            double gap = maxGapMs - (maxGapMs - minGapMs) * diff;

            ThreadLocalRandom random = ThreadLocalRandom.current();
            notes = new ArrayList<>(count);
            long t = now + leadInMs + approachMs;
            int prev = -1, streak = 0;
            for (int i = 0; i < count; i++) {
                int dir;
                do {
                    dir = random.nextInt(4);
                } while (dir == prev && streak >= 2);
                streak = dir == prev ? streak + 1 : 1;
                prev = dir;
                notes.add(new Note(t, dir));

                double step = gap;
                double roll = random.nextDouble();
                if (roll < halfStepChance && gap / 2 >= minHalfStepMs) step = gap / 2;
                else if (roll < halfStepChance + longStepChance) step = gap * 1.5;
                t += (long) step;
            }
            long chartEnd = notes.get(notes.size() - 1).time + goodMs + maxPingCompMs;
            // the chart decides the length; CustomFishing's timer is only a safety net
            deadline = Math.max(deadline, chartEnd + 2000);

            livesLeft = lives;
            judgement = Judgement.READY;
            judgementAt = now;
            flashDir = -1;
            lastRightClick = now; // the reel-in click that started the game also sends a swing

            if (wasdControls) {
                Player player = getPlayer();
                lastInput = player.getCurrentInput();
                DDRInputListener.ensureRegistered();
                DDRInputListener.ACTIVE.put(player.getUniqueId(), this);
                if (freezeMovement) {
                    BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(this::freeze, player.getLocation());
                }
            }
            ready = true;
        }

        @Override
        public void destroy() {
            super.destroy();
            if (!wasdControls) return;
            Player player = getPlayer();
            DDRInputListener.ACTIVE.remove(player.getUniqueId(), this);
            if (freezeMovement) {
                BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(this::unfreeze, player.getLocation());
            }
        }

        // transient modifier: never saved with the player, so a crash can't leave anyone stuck
        private synchronized void freeze() {
            if (!isValid() || frozen) return;
            AttributeInstance speed = getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed == null) return;
            speed.removeModifier(FREEZE_KEY);
            speed.addTransientModifier(new AttributeModifier(FREEZE_KEY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            frozen = true;
        }

        private synchronized void unfreeze() {
            AttributeInstance speed = getPlayer().getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.removeModifier(FREEZE_KEY);
            frozen = false;
        }

        @Override
        public void onInput(Input input) {
            Input prev = lastInput;
            lastInput = input;
            // only key-down edges count; holding a key doesn't repeat
            if (input.isForward() && (prev == null || !prev.isForward())) press(UP);
            if (input.isBackward() && (prev == null || !prev.isBackward())) press(DOWN);
            if (input.isLeft() && (prev == null || !prev.isLeft())) press(LEFT);
            if (input.isRight() && (prev == null || !prev.isRight())) press(RIGHT);
        }

        private long pingComp() {
            if (!pingCompensation) return 0;
            return Math.max(0, Math.min(maxPingCompMs, getPlayer().getPing()));
        }

        // ---- input ----

        @Override
        public boolean internalLeftClick() {
            // CustomFishing would swallow the first left click after every right click (the
            // arm-swing packet that a rod use sends). Filter by time instead so a real
            // left arrow right after a right arrow still counts.
            if (System.currentTimeMillis() - lastRightClick < phantomClickMs) return true;
            return handleLeftClick();
        }

        @Override
        public void internalRightClick() {
            lastRightClick = System.currentTimeMillis();
            handleRightClick();
        }

        @Override
        public boolean handleLeftClick() {
            if (!mouseControls) return false;
            press(LEFT);
            return true;
        }

        @Override
        public void handleRightClick() {
            // never end the game early on reel-in (the default behaviour)
            if (mouseControls) press(RIGHT);
        }

        @Override
        public void handleSwapHand() {
            if (mouseControls) press(UP);
        }

        @Override
        public boolean handleSneak() {
            if (mouseControls) press(DOWN);
            return false;
        }

        @Override
        public boolean handleJump() {
            return false;
        }

        private synchronized void press(int dir) {
            if (!ready || finished || !isValid()) return;
            long now = System.currentTimeMillis();
            long t = now - pingComp();

            Note best = null;
            long bestDelta = Long.MAX_VALUE;
            Note earliestInWindow = null;
            for (int i = nextPending; i < notes.size(); i++) {
                Note n = notes.get(i);
                if (n.state != 0) continue;
                long delta = Math.abs(t - n.time);
                if (n.time - t > goodMs) break; // notes are time-ordered
                if (delta > goodMs) continue;
                if (earliestInWindow == null) earliestInWindow = n;
                if (n.dir == dir && delta < bestDelta) {
                    best = n;
                    bestDelta = delta;
                }
            }

            if (best != null) {
                best.state = 1;
                combo++;
                maxCombo = Math.max(maxCombo, combo);
                boolean perfect = bestDelta <= perfectMs;
                judge(perfect ? Judgement.PERFECT : Judgement.GOOD, now);
                flashDir = dir;
                flashAt = now;
                playSound(perfect ? soundPerfect : soundGood, perfect ? 1.6f : 1.2f);
            } else if (earliestInWindow != null && wrongArrowCostsLife) {
                // wrong arrow while a note is in the box: that note is lost (stops key-mashing)
                earliestInWindow.state = 2;
                miss(now);
            }
            advancePending();
            checkEnd();
        }

        // ---- loop ----

        @Override
        protected synchronized void tick() {
            if (!ready || finished) return;
            long now = System.currentTimeMillis();
            long lateLimit = goodMs + pingComp();
            for (int i = nextPending; i < notes.size(); i++) {
                Note n = notes.get(i);
                if (n.time > now) break;
                if (n.state == 0 && now - n.time > lateLimit) {
                    n.state = 2;
                    miss(now);
                }
            }
            advancePending();
            if (checkEnd()) return;
            try {
                render(now, 300);
            } catch (RuntimeException e) {
                BukkitCustomFishingPlugin.getInstance().getPluginLogger().warn("[DDR] render failed", e);
            }
        }

        private void miss(long now) {
            combo = 0;
            misses++;
            livesLeft--;
            judge(Judgement.MISS, now);
            playSound(soundMiss, 0.6f);
        }

        private void judge(Judgement j, long now) {
            judgement = j;
            judgementAt = now;
        }

        private void advancePending() {
            while (nextPending < notes.size() && notes.get(nextPending).state != 0) nextPending++;
        }

        /** @return true if the game is over */
        private boolean checkEnd() {
            if (finished) return true;
            boolean failed = livesLeft <= 0;
            boolean done = nextPending >= notes.size();
            if (!failed && !done) return false;
            finished = true;
            setGameResult(!failed);
            showFinal(failed ? textFailed : (misses == 0 ? textFullCombo : textCleared));
            endGame();
            return true;
        }

        // ---- display ----

        private void render(long now, long stayMs) {
            StringBuilder track = new StringBuilder();
            String bracket = bracketIdle;
            if (now - judgementAt < 250) {
                bracket = switch (judgement) {
                    case PERFECT -> bracketPerfect;
                    case GOOD -> bracketGood;
                    case MISS -> bracketMiss;
                    default -> bracketIdle;
                };
            }

            // which note (if any) occupies each cell; cell 0 is the target box
            Note[] occupant = new Note[lanes];
            double cellMs = (double) approachMs / (lanes - 1);
            for (int i = nextPending; i < notes.size(); i++) {
                Note n = notes.get(i);
                if (n.state != 0) continue;
                double pos = (n.time - now) / cellMs;
                if (pos >= lanes - 0.5) break;
                int cell = (int) Math.min(lanes - 1, Math.max(0, Math.round(pos)));
                if (occupant[cell] == null) occupant[cell] = n;
            }

            track.append(bracket).append('[');
            if (occupant[0] != null) track.append(cells[occupant[0].dir]);
            else if (flashDir >= 0 && now - flashAt < 150) track.append(flashCells[flashDir]);
            else track.append(targetEmptyCell);
            track.append(bracket).append(']');
            for (int c = 1; c < lanes; c++) {
                track.append(occupant[c] != null ? cells[occupant[c].dir] : emptyCell);
            }

            String status = statusLine(now);
            send(track.toString(), status, stayMs);
        }

        private String statusLine(long now) {
            String j = switch (judgement) {
                case READY -> textReady;
                case PERFECT -> now - judgementAt < 500 ? textPerfect : "";
                case GOOD -> now - judgementAt < 500 ? textGood : "";
                case MISS -> now - judgementAt < 500 ? textMiss : "";
                default -> "";
            };
            if (judgement == Judgement.READY && now >= notes.get(0).time - approachMs) j = "";
            StringBuilder hearts = new StringBuilder();
            for (int i = 0; i < lives; i++) hearts.append(i < livesLeft ? lifeFull : lifeEmpty);
            return statusFormat
                    .replace("{judgement}", j)
                    .replace("{combo}", String.valueOf(combo))
                    .replace("{max_combo}", String.valueOf(maxCombo))
                    .replace("{lives}", hearts.toString())
                    .replace("{misses}", String.valueOf(misses));
        }

        private void showFinal(String text) {
            Player player = getPlayer();
            String summary = "<gray>max combo <white>" + maxCombo + "</white> · misses <white>" + misses + "</white>";
            player.showTitle(Title.title(MM.deserialize(text), MM.deserialize(summary),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
            player.sendActionBar(Component.empty());
        }

        private void send(String trackMm, String statusMm, long stayMs) {
            Player player = getPlayer();
            Component track = MM.deserialize(trackMm);
            Component status = MM.deserialize(statusMm);
            Title.Times times = Title.Times.times(Duration.ZERO, Duration.ofMillis(stayMs), Duration.ZERO);
            player.showTitle(trackInTitle ? Title.title(track, status, times) : Title.title(status, track, times));
            if (tip != null && !tip.isEmpty()) player.sendActionBar(MM.deserialize(tip));
        }

        private void playSound(String sound, float pitch) {
            if (sound == null || sound.isEmpty()) return;
            Player player = getPlayer();
            BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(
                    () -> player.playSound(player, sound, SoundCategory.PLAYERS, 1f, pitch),
                    player.getLocation());
        }
    }
}
