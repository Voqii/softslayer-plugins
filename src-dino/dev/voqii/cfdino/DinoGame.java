package dev.voqii.cfdino;

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
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/**
 * Chrome "no internet" T-Rex style runner. Obstacles scroll right-to-left towards the runner on a
 * floating text display in front of the player (only they can see it). Three height rows:
 *
 *   sky     drifting clouds (scenery) + high birds -> only the top of a jump reaches here; don't mistime it
 *   head    birds at head height                   -> duck under (or clear them at the top of a jump)
 *   ground  cacti                                  -> jump
 *
 * A jump arcs ground -> head -> sky (apex) -> head -> ground.
 *
 * Jump: W / space / right click. Duck: hold S or sneak (pressing it mid-air fast-falls).
 * Clear every obstacle before running out of lives to land the fish.
 *
 * Glyphs are vanilla Unifont emoji (no resource pack). Every cell is exactly 16px: glyph advance
 * (Unifont: trimmed width / 2 + 1) plus padding built from 4px spaces and 5px bold spaces, so the
 * rows stay aligned under the display's centre alignment.
 */
public class DinoGame extends AbstractGame {

    static final int CACTUS = 0, BIRD = 1, HIGH_BIRD = 2;
    private static final int CELL = 16;
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final NamespacedKey FREEZE_KEY = NamespacedKey.fromString("cfdino:freeze");
    // advance widths of 26.3 vanilla Unifont glyphs (measured from unifont_all_no_pua-17.0.01.hex)
    private static final Map<String, Integer> KNOWN_WIDTHS = Map.of(
            "🦖", 8, "🦕", 8, "🦎", 7, "🌵", 6, "🐦", 7, "🦅", 8, "🐟", 8, "🪨", 6, "🌥", 8, "🌤", 8);

    // course
    private final int obstaclesMin, obstaclesMax;
    private final int lanes, playerLane;
    private final double startSpeed, endSpeed; // cells per second
    private final long leadInMs, minGapMs, maxGapMs;
    private final double gapTighten;
    private final double birdChance, highBirdShare, doubleCactusChance;
    private final int birdsAfter;
    // runner
    private final long jumpMs, fastFallMs;
    private final double apexShare;
    private final double cloudSpeed, cloudChance;
    private final double hitbox;
    private final int lives;
    private final long invulnMs;
    private final boolean pingCompensation;
    private final long maxPingCompMs;
    private final boolean freezeMovement;
    // display
    private final double distance, heightOffset;
    private final float scale;
    private final Color background;
    private final String[] glyph = new String[6];
    private final int[] glyphWidth = new int[6];
    private final String[] glyphColor = new String[6];
    private final String groundColor, hitColor;
    private final String statusFormat, textReady, lifeFull, lifeEmpty;
    private final String textWin, textPerfect, textLose;
    private final String tip;
    private final String soundJump, soundHit, soundPoint;

    // glyph slots
    private static final int G_RUNNER = 0, G_DUCK = 1, G_CACTUS = 2, G_BIRD = 3, G_HIGH = 4, G_CLOUD = 5;
    private static final int ROW_GROUND = 0, ROW_HEAD = 1, ROW_SKY = 2;
    private static final String ZWNJ = "\u200c"; // zero-width, non-blank: keeps space-only rows from collapsing

    public DinoGame(String id, Section section) {
        super(id, GameBasics.builder()
                .difficulty(MathValue.auto(section.get("difficulty", "20~80"), true))
                .time(MathValue.auto(section.get("time", 60), true))
                .build());

        String obstacles = String.valueOf(section.get("arguments.obstacles", "10~16"));
        if (obstacles.contains("~")) {
            String[] split = obstacles.split("~");
            obstaclesMin = Integer.parseInt(split[0].trim());
            obstaclesMax = Integer.parseInt(split[1].trim());
        } else {
            obstaclesMin = obstaclesMax = Integer.parseInt(obstacles.trim());
        }
        lanes = Math.max(6, section.getInt("arguments.lanes", 14));
        playerLane = Math.max(0, Math.min(lanes - 3, section.getInt("arguments.runner-lane", 2)));
        startSpeed = section.getDouble("arguments.start-speed", 7.0);
        endSpeed = section.getDouble("arguments.end-speed", 10.0);
        leadInMs = ms(section.getDouble("arguments.lead-in", 1.5));
        minGapMs = ms(section.getDouble("arguments.min-gap", 0.9));
        maxGapMs = ms(section.getDouble("arguments.max-gap", 1.6));
        gapTighten = section.getDouble("arguments.gap-tighten", 0.25);
        birdChance = section.getDouble("arguments.bird-chance", 0.3);
        highBirdShare = section.getDouble("arguments.high-bird-share", 0.5);
        doubleCactusChance = section.getDouble("arguments.double-cactus-chance", 0.15);
        birdsAfter = section.getInt("arguments.birds-after", 3);
        jumpMs = ms(section.getDouble("arguments.jump-time", 0.6));
        fastFallMs = ms(section.getDouble("arguments.fast-fall", 0.08));
        apexShare = Math.max(0.1, Math.min(0.9, section.getDouble("arguments.apex-share", 0.4)));
        cloudSpeed = section.getDouble("arguments.cloud-speed", 0.35);
        cloudChance = section.getDouble("arguments.cloud-chance", 0.04);
        hitbox = section.getDouble("arguments.hitbox", 0.35);
        lives = Math.max(1, section.getInt("arguments.lives", 3));
        invulnMs = ms(section.getDouble("arguments.invulnerable-time", 1.0));
        pingCompensation = section.getBoolean("arguments.ping-compensation", true);
        maxPingCompMs = ms(section.getDouble("arguments.max-ping-compensation", 0.2));
        freezeMovement = section.getBoolean("freeze-movement", true);

        distance = section.getDouble("display.distance", 3.5);
        heightOffset = section.getDouble("display.height-offset", 0.4);
        scale = section.getDouble("display.scale", 0.6).floatValue();
        String bg = section.getString("display.background", "#000000");
        int alpha = Math.max(0, Math.min(255, section.getInt("display.background-alpha", 150)));
        java.awt.Color c = java.awt.Color.decode(bg);
        background = Color.fromARGB(alpha, c.getRed(), c.getGreen(), c.getBlue());

        String[] names = {"runner", "duck", "cactus", "bird", "high-bird", "cloud"};
        String[] defGlyph = {"🦖", "🦎", "🌵", "🐦", "🦅", "🌥"};
        String[] defColor = {"#e0e0e0", "#e0e0e0", "#6fcf6f", "#e0b070", "#d08060", "#6c7a96"};
        for (int i = 0; i < 6; i++) {
            glyph[i] = section.getString("glyphs." + names[i], defGlyph[i]);
            glyphWidth[i] = section.getInt("glyph-widths." + names[i], KNOWN_WIDTHS.getOrDefault(glyph[i], 8));
            glyphColor[i] = section.getString("colors." + names[i], defColor[i]);
        }
        groundColor = section.getString("colors.ground", "#8a8a8a");
        hitColor = section.getString("colors.hit", "#ff4040");

        statusFormat = section.getString("text.status", "{lives}  <gray>{cleared}<dark_gray>/</dark_gray>{total}</gray> 🏁");
        textReady = section.getString("text.ready", "<yellow>Get ready...");
        lifeFull = section.getString("text.life", "<red>❤");
        lifeEmpty = section.getString("text.life-lost", "<dark_gray>❤");
        textWin = section.getString("text.cleared", "<green><b>CAUGHT!</b>");
        textPerfect = section.getString("text.perfect", "<gold><b>FLAWLESS!</b>");
        textLose = section.getString("text.failed", "<red><b>SPLAT!</b>");
        tip = section.getString("tip",
                "<gray>Jump <white><key:key.forward></white>/<white><key:key.jump></white>  ·  " +
                "Duck <white><key:key.back></white>/<white><key:key.sneak></white>");
        soundJump = section.getString("sound.jump", "minecraft:block.note_block.bit");
        soundHit = section.getString("sound.hit", "minecraft:entity.player.hurt");
        soundPoint = section.getString("sound.point", "minecraft:block.note_block.hat");
    }

    private static long ms(double seconds) {
        return Math.round(seconds * 1000);
    }

    private static boolean padable(int n) {
        for (int b = 0; b * 5 <= n; b++) if ((n - b * 5) % 4 == 0) return true;
        return false;
    }

    /** n pixels of blank space from 4px spaces and 5px bold spaces (n must be padable). */
    private static String spaces(int n) {
        for (int b = 0; b * 5 <= n; b++) {
            if ((n - b * 5) % 4 == 0) {
                return " ".repeat((n - b * 5) / 4) + (b > 0 ? "<b>" + " ".repeat(b) + "</b>" : "");
            }
        }
        return "";
    }

    private static String cell(String glyph, int width, String color) {
        int pad = Math.max(0, CELL - width);
        int left = pad / 2;
        while (left > 0 && !(padable(left) && padable(pad - left))) left--;
        return spaces(left) + "<" + color + ">" + glyph + "</" + color + ">" + spaces(pad - left);
    }

    @Override
    public BiFunction<CustomFishingHook, GameSetting, AbstractGamingPlayer> gamingPlayerProvider() {
        return DinoPlayer::new;
    }

    private final class DinoPlayer extends AbstractGamingPlayer implements DinoInputListener.InputSink {

        // not initialised in declarations: the super constructor schedules the task before they would run
        private volatile boolean ready;
        private boolean finished;
        private long firstArrival, rampMs;
        private List<long[]> obstacles; // {arrival time at runner lane, type, resolved}
        private int nextUnresolved, cleared, livesLeft, hits;
        private long invulnUntil, hitAt;
        private List<long[]> jumps, ducks; // {start, end} in player time
        private boolean duckHeld;
        private Input lastInput;
        private TextDisplay display;
        private boolean frozen;
        private List<double[]> clouds; // {x}
        private long lastRender;

        DinoPlayer(CustomFishingHook hook, GameSetting setting) {
            super(hook, setting);
            long now = System.currentTimeMillis();
            double diff = Math.max(0, Math.min(100, setting.difficulty())) / 100.0;
            int count = (int) Math.round(obstaclesMin + (obstaclesMax - obstaclesMin) * diff);
            double gapScale = 1 - gapTighten * diff;
            long minGap = Math.max(jumpMs + 300, (long) (minGapMs * gapScale));
            long maxGap = Math.max(minGap, (long) (maxGapMs * gapScale));

            ThreadLocalRandom random = ThreadLocalRandom.current();
            obstacles = new ArrayList<>();
            firstArrival = now + leadInMs + (long) ((lanes - 1 - playerLane) / startSpeed * 1000);
            rampMs = Math.max(1, count * (minGap + maxGap) / 2);
            long t = firstArrival;
            for (int i = 0; i < count; i++) {
                int type = CACTUS;
                if (i >= birdsAfter && random.nextDouble() < birdChance) {
                    type = random.nextDouble() < highBirdShare ? HIGH_BIRD : BIRD;
                }
                obstacles.add(new long[]{t, type, 0});
                if (type == CACTUS && i >= 2 && random.nextDouble() < doubleCactusChance) {
                    // a second cactus right behind the first: one jump clears both
                    obstacles.add(new long[]{t + (long) (1000 / speedAt(t)), CACTUS, 0});
                }
                t += random.nextLong(minGap, maxGap + 1);
            }
            long lastArrival = obstacles.get(obstacles.size() - 1)[0];
            deadline = Math.max(deadline, lastArrival + 3000);

            jumps = new ArrayList<>();
            ducks = new ArrayList<>();
            clouds = new ArrayList<>();
            for (int i = 0; i < 3; i++) clouds.add(new double[]{random.nextDouble(1, lanes - 1)});
            livesLeft = lives;
            Player player = getPlayer();
            lastInput = player.getCurrentInput();
            duckHeld = lastInput.isBackward() || lastInput.isSneak();
            if (duckHeld) ducks.add(new long[]{now, Long.MAX_VALUE});
            DinoInputListener.ensureRegistered();
            DinoInputListener.ACTIVE.put(player.getUniqueId(), this);
            ready = true;
        }

        @Override
        public void arrangeTask() {
            // main thread: the game drives an entity
            this.task = BukkitCustomFishingPlugin.getInstance().getScheduler().sync()
                    .runRepeating(this, 1, 1, hook.getHookEntity().getLocation());
        }

        private double speedAt(long t) {
            double p = Math.max(0, Math.min(1, (double) (t - firstArrival) / rampMs));
            return startSpeed + (endSpeed - startSpeed) * p;
        }

        private long pingComp() {
            if (!pingCompensation) return 0;
            return Math.max(0, Math.min(maxPingCompMs, getPlayer().getPing()));
        }

        // ---- runner state (player time) ----

        private boolean airborne(long t) {
            for (long[] j : jumps) if (j[0] <= t && t < j[1]) return true;
            return false;
        }

        /** Which row the runner's body is in: ground (standing/ducking), head height or sky (jump apex). */
        private int rowAt(long t) {
            for (long[] j : jumps) {
                if (j[0] <= t && t < j[1]) {
                    double p = (double) (t - j[0]) / jumpMs;
                    double edge = (1 - apexShare) / 2;
                    return p >= edge && p < 1 - edge ? ROW_SKY : ROW_HEAD;
                }
            }
            return ROW_GROUND;
        }

        private boolean ducking(long t) {
            if (airborne(t)) return false;
            for (long[] d : ducks) if (d[0] <= t && t < d[1]) return true;
            return false;
        }

        private boolean safe(int type, long t) {
            int row = rowAt(t);
            return switch (type) {
                case CACTUS -> row != ROW_GROUND;
                case BIRD -> ducking(t) || row == ROW_SKY; // a standing runner's head is at bird height
                default -> row != ROW_SKY;
            };
        }

        // ---- input ----

        @Override
        public void onInput(Input input) {
            if (!ready || finished) return;
            Input prev = lastInput;
            lastInput = input;
            boolean jumpPress = (input.isForward() && !prev.isForward()) || (input.isJump() && !prev.isJump());
            boolean duck = input.isBackward() || input.isSneak();
            long t = System.currentTimeMillis() - pingComp();
            if (duck && !duckHeld) {
                ducks.add(new long[]{t, Long.MAX_VALUE});
                for (long[] j : jumps) if (j[0] <= t && t < j[1]) j[1] = Math.min(j[1], t + fastFallMs);
            } else if (!duck && duckHeld) {
                for (long[] d : ducks) if (d[1] == Long.MAX_VALUE) d[1] = t;
            }
            duckHeld = duck;
            if (jumpPress) jump(t);
        }

        private void jump(long t) {
            if (airborne(t)) return;
            jumps.add(new long[]{t, t + jumpMs});
            playSound(soundJump, 1.4f, 0.6f);
        }

        @Override
        public void internalRightClick() {
            handleRightClick();
        }

        @Override
        public void handleRightClick() {
            // right click jumps too; never end the game early (CustomFishing's default)
            if (ready && !finished) jump(System.currentTimeMillis() - pingComp());
        }

        @Override
        public boolean handleJump() {
            return true; // jump is read from input; don't hop in the world
        }

        // ---- loop (main thread) ----

        @Override
        protected void tick() {
            if (!ready || finished) return;
            long now = System.currentTimeMillis();
            if (display == null) setup();
            long g = now - pingComp(); // judge in the player's time

            while (nextUnresolved < obstacles.size()) {
                long[] o = obstacles.get(nextUnresolved);
                long half = (long) (hitbox / speedAt(o[0]) * 1000);
                if (g < o[0] + half) break;
                boolean hit = false;
                if (o[0] + half >= invulnUntil) {
                    for (long s = Math.max(o[0] - half, invulnUntil); s <= o[0] + half; s += 10) {
                        if (!safe((int) o[1], s)) {
                            hit = true;
                            break;
                        }
                    }
                }
                o[2] = 1;
                nextUnresolved++;
                if (hit) {
                    hits++;
                    livesLeft--;
                    invulnUntil = o[0] + invulnMs;
                    hitAt = now;
                    playSound(soundHit, 1f, 1f);
                    if (livesLeft <= 0) {
                        finish(false);
                        return;
                    }
                } else {
                    cleared++;
                    playSound(soundPoint, 1.8f, 0.5f);
                }
            }
            if (nextUnresolved >= obstacles.size()) {
                finish(true);
                return;
            }
            jumps.removeIf(j -> j[1] < g - 2000);
            ducks.removeIf(d -> d[1] < g - 2000);
            try {
                render(now, g);
            } catch (RuntimeException e) {
                BukkitCustomFishingPlugin.getInstance().getPluginLogger().warn("[Dino] render failed", e);
            }
        }

        private void setup() {
            Player player = getPlayer();
            Location eye = player.getEyeLocation();
            Location at = eye.clone().add(eye.getDirection().normalize().multiply(distance)).add(0, heightOffset, 0);
            Plugin plugin = DinoInputListener.plugin();
            display = at.getWorld().spawn(at, TextDisplay.class, td -> {
                td.setPersistent(false);
                td.setVisibleByDefault(false);
                td.setBillboard(Display.Billboard.CENTER);
                td.setAlignment(TextDisplay.TextAlignment.CENTER);
                td.setLineWidth(4000);
                td.setBackgroundColor(background);
                td.setShadowed(false);
                td.setSeeThrough(false);
                td.setBrightness(new Display.Brightness(15, 15));
                td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(scale, scale, scale), new AxisAngle4f()));
                td.text(Component.empty());
            });
            if (plugin != null) player.showEntity(plugin, display);
            if (freezeMovement) freeze(player);
        }

        private void render(long now, long g) {
            double v = speedAt(now);
            String[][] rows = new String[3][lanes];

            // clouds drift slower than the course (parallax); purely scenery
            double dt = lastRender == 0 ? 0 : (now - lastRender) / 1000.0;
            lastRender = now;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (double[] cl : clouds) cl[0] -= v * cloudSpeed * dt;
            clouds.removeIf(cl -> cl[0] < -0.5);
            double rightmost = clouds.stream().mapToDouble(cl -> cl[0]).max().orElse(-10);
            if (rightmost < lanes - 4 && random.nextDouble() < cloudChance) clouds.add(new double[]{lanes - 0.5});
            for (double[] cl : clouds) {
                int c = (int) Math.round(cl[0]);
                if (c >= 0 && c < lanes) rows[ROW_SKY][c] = cell(glyph[G_CLOUD], glyphWidth[G_CLOUD], glyphColor[G_CLOUD]);
            }

            for (long[] o : obstacles) {
                double x = playerLane + v * (o[0] - now) / 1000.0;
                if (x > lanes - 0.5) break;
                int c = (int) Math.round(x);
                if (c < 0) continue;
                int type = (int) o[1];
                int gi = type == CACTUS ? G_CACTUS : type == BIRD ? G_BIRD : G_HIGH;
                int row = type == CACTUS ? ROW_GROUND : type == BIRD ? ROW_HEAD : ROW_SKY;
                rows[row][c] = cell(glyph[gi], glyphWidth[gi], glyphColor[gi]);
            }

            boolean blinking = now - hitAt < invulnMs && ((now - hitAt) / 150) % 2 == 0;
            int row = rowAt(g);
            int gi = row == ROW_GROUND && ducking(g) ? G_DUCK : G_RUNNER;
            rows[row][playerLane] = cell(glyph[gi], glyphWidth[gi], blinking ? hitColor : glyphColor[gi]);

            String blank = spaces(CELL);
            StringBuilder board = new StringBuilder();
            for (int r = ROW_SKY; r >= ROW_GROUND; r--) {
                StringBuilder line = new StringBuilder(ZWNJ);
                for (int c = 0; c < lanes; c++) line.append(rows[r][c] != null ? rows[r][c] : blank);
                line.append(ZWNJ);
                board.append('\n');
                if (r == ROW_GROUND) board.append("<u><").append(groundColor).append(">").append(line)
                        .append("</").append(groundColor).append("></u>");
                else board.append(line);
            }

            String status;
            if (now < firstArrival - (long) ((lanes - 1 - playerLane) / startSpeed * 1000)) {
                status = textReady;
            } else {
                StringBuilder hearts = new StringBuilder();
                for (int i = 0; i < lives; i++) hearts.append(i < livesLeft ? lifeFull : lifeEmpty);
                status = statusFormat
                        .replace("{lives}", hearts.toString())
                        .replace("{cleared}", String.valueOf(cleared))
                        .replace("{total}", String.valueOf(obstacles.size()));
            }
            display.text(MM.deserialize(status + board));
            if (tip != null && !tip.isEmpty()) getPlayer().sendActionBar(MM.deserialize(tip));
        }

        private void finish(boolean success) {
            finished = true;
            setGameResult(success);
            Player player = getPlayer();
            String summary = "<gray>cleared <white>" + cleared + "</white>/" + obstacles.size() + " · hits <white>" + hits + "</white>";
            player.showTitle(Title.title(MM.deserialize(success ? (hits == 0 ? textPerfect : textWin) : textLose),
                    MM.deserialize(summary), Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
            player.sendActionBar(Component.empty());
            endGame();
        }

        // ---- cleanup ----

        @Override
        public void destroy() {
            super.destroy();
            Player player = getPlayer();
            DinoInputListener.ACTIVE.remove(player.getUniqueId(), this);
            Runnable cleanup = () -> {
                if (display != null) {
                    display.remove();
                    display = null;
                }
                unfreeze(player);
            };
            if (Bukkit.isPrimaryThread()) cleanup.run();
            else BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(cleanup, player.getLocation());
        }

        // transient modifiers: never saved with the player, so a crash can't leave anyone stuck
        private void freeze(Player player) {
            if (frozen) return;
            for (Attribute attribute : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) continue;
                instance.removeModifier(FREEZE_KEY);
                instance.addTransientModifier(new AttributeModifier(FREEZE_KEY, -1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
            frozen = true;
        }

        private void unfreeze(Player player) {
            for (Attribute attribute : new Attribute[]{Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH}) {
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null) instance.removeModifier(FREEZE_KEY);
            }
            frozen = false;
        }

        private void playSound(String sound, float pitch, float volume) {
            if (sound == null || sound.isEmpty()) return;
            Player player = getPlayer();
            if (Bukkit.isPrimaryThread()) player.playSound(player, sound, SoundCategory.PLAYERS, volume, pitch);
            else BukkitCustomFishingPlugin.getInstance().getScheduler().sync().run(
                    () -> player.playSound(player, sound, SoundCategory.PLAYERS, volume, pitch), player.getLocation());
        }
    }
}
