package com.voqii.timingghost;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a ghost out of display entities: no hitbox, no collision, and hidden from everyone but the racer.
 * Every part is teleported to the same spot and yaw each tick; each part's transformation places it
 * relative to that spot, and the client rotates the whole set together by the yaw.
 */
final class GhostRenderer {

    private static final int SMOOTHING_TICKS = 2;
    private static final double JUMP_DISTANCE_SQ = 8 * 8;

    private record Box(float x, float y, float z, float w, float h, float d) {
    }

    // Local space: +Z is forward, Y=0 is the bottom of the boat / the figure's feet.
    private static final List<Box> BOAT = List.of(
            new Box(-0.6f, 0.0f, -0.95f, 1.2f, 0.1f, 1.9f),   // floor
            new Box(-0.6f, 0.1f, -0.95f, 0.1f, 0.45f, 1.9f),  // left side
            new Box(0.5f, 0.1f, -0.95f, 0.1f, 0.45f, 1.9f),   // right side
            new Box(-0.5f, 0.1f, 0.85f, 1.0f, 0.45f, 0.1f),   // bow
            new Box(-0.5f, 0.1f, -0.95f, 1.0f, 0.45f, 0.1f)); // stern
    private static final List<Box> FIGURE = List.of(
            new Box(-0.25f, 0.0f, -0.15f, 0.5f, 1.45f, 0.3f)); // body
    // Height of the bottom of the driver's head above the ghost's origin.
    private static final float BOAT_HEAD_Y = 0.95f;
    private static final float FIGURE_HEAD_Y = 1.45f;

    private final TimingGhost plugin;
    private final Player viewer;
    private final GhostRecording rec;
    private final List<Display> parts = new ArrayList<>();
    private TextDisplay label;
    private double lastX, lastY, lastZ;

    GhostRenderer(TimingGhost plugin, Player viewer, GhostRecording rec) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.rec = rec;
    }

    GhostRecording recording() {
        return rec;
    }

    /** Moves the ghost to where the recording was at this many ms into the run. */
    void showAt(long elapsed, double[] scratch) {
        World world = Bukkit.getWorld(rec.world);
        if (world == null || world != viewer.getWorld()) {
            remove();
            return;
        }
        rec.sample(elapsed, scratch);
        Location loc = new Location(world, scratch[0], scratch[1], scratch[2], (float) scratch[3], 0f);
        if (!loc.isChunkLoaded()) {
            return;
        }
        if (parts.isEmpty() || parts.stream().anyMatch(p -> !p.isValid())) {
            remove();
            spawn(loc);
            return;
        }
        double dx = loc.getX() - lastX, dy = loc.getY() - lastY, dz = loc.getZ() - lastZ;
        boolean jump = dx * dx + dy * dy + dz * dz > JUMP_DISTANCE_SQ; // reset/teleport in the run: snap, don't glide
        for (Display part : parts) {
            if (jump) {
                part.setTeleportDuration(0);
            } else if (part.getTeleportDuration() != SMOOTHING_TICKS) {
                part.setTeleportDuration(SMOOTHING_TICKS);
            }
            part.teleport(loc);
        }
        lastX = loc.getX();
        lastY = loc.getY();
        lastZ = loc.getZ();
    }

    /** How far behind the recording the client draws the ghost, in ms, because of position smoothing. */
    static long smoothingLagMillis() {
        return SMOOTHING_TICKS * 50L;
    }

    void remove() {
        for (Display part : parts) {
            part.remove();
        }
        parts.clear();
        label = null;
    }

    private void spawn(Location loc) {
        Config cfg = plugin.config();
        BlockData block = cfg.material().createBlockData();
        for (Box box : rec.boat ? BOAT : FIGURE) {
            parts.add(loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
                setup(e, cfg, true);
                e.setBlock(block);
                e.setTransformation(transform(box.x, box.y, box.z, box.w, box.h, box.d));
            }));
        }
        float headY = rec.boat ? BOAT_HEAD_Y : FIGURE_HEAD_Y;
        parts.add(loc.getWorld().spawn(loc, ItemDisplay.class, e -> {
            setup(e, cfg, false);
            e.setItemStack(head());
            // NONE draws the head model 0.5 tall, hanging from y-0.5 to y0 around the origin.
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(new Transformation(new Vector3f(0, headY + 0.5f, 0),
                    new AxisAngle4f((float) Math.toRadians(cfg.headYaw()), 0, 1, 0), new Vector3f(1, 1, 1), new AxisAngle4f()));
        }));
        if (cfg.showLabel()) {
            label = loc.getWorld().spawn(loc, TextDisplay.class, e -> {
                setup(e, cfg, false);
                e.setBillboard(Display.Billboard.CENTER);
                e.text(Component.text("Ghost · " + rec.ownerName + " · " + TimingGhost.formatTime(rec.finishTime), NamedTextColor.AQUA));
                e.setBackgroundColor(Color.fromARGB(0x40000000));
                e.setTransformation(transform(0, headY + 0.75f, 0, 1, 1, 1));
            });
            parts.add(label);
        }
        for (Display part : parts) {
            viewer.showEntity(plugin, part);
        }
        lastX = loc.getX();
        lastY = loc.getY();
        lastZ = loc.getZ();
    }

    private static void setup(Display e, Config cfg, boolean glows) {
        e.setPersistent(false);
        e.setVisibleByDefault(false);
        e.setTeleportDuration(SMOOTHING_TICKS);
        e.setBrightness(new Display.Brightness(15, 15));
        if (glows && cfg.glow()) {
            e.setGlowing(true);
            e.setGlowColorOverride(cfg.glowColor());
        }
    }

    private ItemStack head() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (rec.skinValue != null) {
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            PlayerProfile profile = Bukkit.createProfile(rec.owner, rec.ownerName);
            profile.setProperty(new ProfileProperty("textures", rec.skinValue, rec.skinSignature));
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Offset (x,y,z) and scale (w,h,d); a block display's box starts at its corner, so that's the box's corner. */
    private static Transformation transform(float x, float y, float z, float w, float h, float d) {
        return new Transformation(new Vector3f(x, y, z), new AxisAngle4f(), new Vector3f(w, h, d), new AxisAngle4f());
    }
}
