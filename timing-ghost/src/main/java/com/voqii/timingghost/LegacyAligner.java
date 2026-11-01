package com.voqii.timingghost;

import me.makkuusen.timing.system.track.Track;
import me.makkuusen.timing.system.track.regions.TrackRegion;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Version 1 ghosts were saved without an anchor, so after a {@code /te move} nothing says how far the track went.
 * {@code /te move} shifts every region by the same whole-block offset, so this looks for the offset that puts the
 * recorded path back through the track's start, every checkpoint and the finish as they are now.
 */
final class LegacyAligner {

    /** Inclusive block box, the shape TimingSystem's cuboid regions test against. */
    record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
    }

    // The first sample is a tick after the start event, so it can sit a few blocks past the start line on ice.
    private static final int MARGIN_XZ = 8;
    private static final int MARGIN_Y = 4;
    private static final int MAX_CANDIDATES = 2_000_000;
    private static final int MAX_EXACT_CHECKS = 64;
    private static final double STEP = 0.5; // fills the gap between ticks so a fast boat can't skip a thin checkpoint

    private LegacyAligner() {
    }

    /** The whole-block shift from the recording to the track as it is now, or null if no shift fits. */
    static int[] align(GhostRecording rec, Track track) {
        List<TrackRegion> required = required(track);
        if (required.isEmpty() || rec.size() == 0) {
            return null;
        }

        List<Box> boxes = new ArrayList<>();
        for (TrackRegion r : required) {
            boxes.add(box(r.getMinP(), r.getMaxP()));
        }
        int[] path = pathBlocks(rec);
        Location probe = new Location(null, 0, 0, 0);
        // Poly regions only give us their bounding box here, so every pick is confirmed with TimingSystem's own test.
        return search(path, boxes, rec.x(0), rec.y(0), rec.z(0), (ox, oy, oz) -> passes(rec, required, ox, oy, oz, probe));
    }

    /** Whether the path, shifted this much, still runs through the track's start, checkpoints and finish. */
    static boolean fits(GhostRecording rec, Track track, int ox, int oy, int oz) {
        List<TrackRegion> required = required(track);
        return !required.isEmpty() && passes(rec, required, ox, oy, oz, new Location(null, 0, 0, 0));
    }

    /** Start first (the search is built around it), then the finish and every checkpoint. Empty without a start. */
    private static List<TrackRegion> required(Track track) {
        var regions = track.getTrackRegions();
        List<TrackRegion> required = new ArrayList<>();
        TrackRegion start = regions.getStart().filter(TrackRegion::isDefined).orElse(null);
        if (start == null || start.getMinP() == null || start.getMaxP() == null) {
            return required;
        }
        required.add(start);
        regions.getRegion(TrackRegion.RegionType.END).filter(TrackRegion::isDefined).ifPresent(required::add);
        required.addAll(regions.getRegions(TrackRegion.RegionType.CHECKPOINT));
        required.removeIf(r -> !r.isDefined() || r.getMinP() == null || r.getMaxP() == null);
        return required;
    }

    interface Exact {
        boolean test(int ox, int oy, int oz);
    }

    /**
     * Pure search, no Bukkit: every candidate offset puts the first sample near boxes.get(0) (the start), and must put
     * some path block inside every box. Returns {0,0,0} when that already works, otherwise the passing offset nearest
     * the middle of all passing offsets, confirmed by exact. Null when nothing passes.
     */
    static int[] search(int[] path, List<Box> boxes, double firstX, double firstY, double firstZ, Exact exact) {
        if (boxes.isEmpty() || path.length == 0) {
            return null;
        }
        Box start = boxes.get(0);
        int fx = (int) Math.floor(firstX), fy = (int) Math.floor(firstY), fz = (int) Math.floor(firstZ);
        int x0 = start.x0 - fx - MARGIN_XZ, x1 = start.x1 - fx + MARGIN_XZ;
        int y0 = start.y0 - fy - MARGIN_Y, y1 = start.y1 - fy + MARGIN_Y;
        int z0 = start.z0 - fz - MARGIN_XZ, z1 = start.z1 - fz + MARGIN_XZ;
        int nx = x1 - x0 + 1, ny = y1 - y0 + 1, nz = z1 - z0 + 1;
        long n = (long) nx * ny * nz;
        if (n > MAX_CANDIDATES) {
            return null;
        }
        boolean[] alive = new boolean[(int) n];
        Arrays.fill(alive, true);
        boolean[] hit = new boolean[(int) n];
        // Offset o hits box B through path block p when p + o is in B, i.e. o is in B - p.
        for (Box b : boxes) {
            Arrays.fill(hit, false);
            for (int i = 0; i < path.length; i += 3) {
                int px = path[i], py = path[i + 1], pz = path[i + 2];
                int ax = Math.max(x0, b.x0 - px), bx = Math.min(x1, b.x1 - px);
                if (ax > bx) continue;
                int ay = Math.max(y0, b.y0 - py), by = Math.min(y1, b.y1 - py);
                if (ay > by) continue;
                int az = Math.max(z0, b.z0 - pz), bz = Math.min(z1, b.z1 - pz);
                if (az > bz) continue;
                for (int ox = ax; ox <= bx; ox++) {
                    for (int oy = ay; oy <= by; oy++) {
                        int base = ((ox - x0) * ny + (oy - y0)) * nz - z0;
                        for (int oz = az; oz <= bz; oz++) {
                            hit[base + oz] = true;
                        }
                    }
                }
            }
            boolean any = false;
            for (int i = 0; i < alive.length; i++) {
                alive[i] &= hit[i];
                any |= alive[i];
            }
            if (!any) {
                return null;
            }
        }

        List<int[]> passing = new ArrayList<>();
        double cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < alive.length; i++) {
            if (alive[i]) {
                int ox = x0 + i / (ny * nz), oy = y0 + (i / nz) % ny, oz = z0 + i % nz;
                passing.add(new int[]{ox, oy, oz});
                cx += ox;
                cy += oy;
                cz += oz;
            }
        }
        // A track that never moved fits with no shift; keep it exactly where it was recorded.
        if (0 >= x0 && 0 <= x1 && 0 >= y0 && 0 <= y1 && 0 >= z0 && 0 <= z1
                && alive[((-x0) * ny + (-y0)) * nz + (-z0)] && exact.test(0, 0, 0)) {
            return new int[]{0, 0, 0};
        }
        double mx = cx / passing.size(), my = cy / passing.size(), mz = cz / passing.size();
        passing.sort(Comparator.comparingDouble(o -> sq(o[0] - mx) + sq(o[1] - my) + sq(o[2] - mz)));
        for (int i = 0; i < passing.size() && i < MAX_EXACT_CHECKS; i++) {
            int[] o = passing.get(i);
            if (exact.test(o[0], o[1], o[2])) {
                return o;
            }
        }
        return null;
    }

    /** Block coordinates along the path, filled in between ticks, consecutive repeats dropped: x,y,z,x,y,z... */
    static int[] pathBlocks(GhostRecording rec) {
        int[] buf = new int[Math.max(48, rec.size() * 6)];
        int len = 0;
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int i = 0; i < rec.size(); i++) {
            double ax = i == 0 ? rec.x(0) : rec.x(i - 1), ay = i == 0 ? rec.y(0) : rec.y(i - 1), az = i == 0 ? rec.z(0) : rec.z(i - 1);
            double dx = rec.x(i) - ax, dy = rec.y(i) - ay, dz = rec.z(i) - az;
            int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / STEP));
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                int bx = (int) Math.floor(ax + dx * t), by = (int) Math.floor(ay + dy * t), bz = (int) Math.floor(az + dz * t);
                if (bx == lx && by == ly && bz == lz) continue;
                if (len + 3 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[len++] = bx;
                buf[len++] = by;
                buf[len++] = bz;
                lx = bx;
                ly = by;
                lz = bz;
            }
        }
        return Arrays.copyOf(buf, len);
    }

    private static boolean passes(GhostRecording rec, List<TrackRegion> required, int ox, int oy, int oz, Location probe) {
        for (TrackRegion r : required) {
            boolean hit = false;
            for (int i = 0; i < rec.size() && !hit; i++) {
                double ax = i == 0 ? rec.x(0) : rec.x(i - 1), ay = i == 0 ? rec.y(0) : rec.y(i - 1), az = i == 0 ? rec.z(0) : rec.z(i - 1);
                double dx = rec.x(i) - ax, dy = rec.y(i) - ay, dz = rec.z(i) - az;
                int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / STEP));
                for (int s = 1; s <= steps && !hit; s++) {
                    double t = (double) s / steps;
                    probe.set(ax + dx * t + ox, ay + dy * t + oy, az + dz * t + oz);
                    hit = r.contains(probe);
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private static Box box(Location a, Location b) {
        return new Box(Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
    }

    private static double sq(double v) {
        return v * v;
    }
}
