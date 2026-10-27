package com.voqii.timingghost;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * One recorded run: a position sample per server tick, keyed by milliseconds since the
 * TimingSystem start time, so playback lines up with TimingSystem's own timer.
 */
public final class GhostRecording {

    private static final int MAGIC = 0x47485354; // "GHST"
    private static final int VERSION = 1;

    public final int trackId;
    public final UUID owner;
    public final String ownerName;
    public final String world;
    public final boolean boat;
    public long finishTime; // ms, TimingSystem's finish time; -1 while still recording
    public String skinValue;
    public String skinSignature;

    private int size;
    private long[] times;
    private double[] xs, ys, zs;
    private float[] yaws;

    public GhostRecording(int trackId, UUID owner, String ownerName, String world, boolean boat, int capacity) {
        this.trackId = trackId;
        this.owner = owner;
        this.ownerName = ownerName;
        this.world = world;
        this.boat = boat;
        this.finishTime = -1;
        int cap = Math.max(capacity, 16);
        times = new long[cap];
        xs = new double[cap];
        ys = new double[cap];
        zs = new double[cap];
        yaws = new float[cap];
    }

    public int size() {
        return size;
    }

    public void add(long time, double x, double y, double z, float yaw) {
        // Ticks can share a timestamp; keep the latest position for it.
        if (size > 0 && time <= times[size - 1]) {
            time = times[size - 1];
            size--;
        }
        if (size == times.length) {
            int cap = size * 2;
            times = Arrays.copyOf(times, cap);
            xs = Arrays.copyOf(xs, cap);
            ys = Arrays.copyOf(ys, cap);
            zs = Arrays.copyOf(zs, cap);
            yaws = Arrays.copyOf(yaws, cap);
        }
        times[size] = time;
        xs[size] = x;
        ys[size] = y;
        zs[size] = z;
        yaws[size] = yaw;
        size++;
    }

    /** Fills out[0..3] with x, y, z, yaw at the given time, clamped to the recording's ends. */
    public void sample(long time, double[] out) {
        if (size == 0) {
            return;
        }
        if (time <= times[0]) {
            set(out, 0);
            return;
        }
        if (time >= times[size - 1]) {
            set(out, size - 1);
            return;
        }
        int hi = Arrays.binarySearch(times, 0, size, time);
        if (hi >= 0) {
            set(out, hi);
            return;
        }
        hi = -hi - 1;
        int lo = hi - 1;
        double t = (double) (time - times[lo]) / (times[hi] - times[lo]);
        out[0] = xs[lo] + (xs[hi] - xs[lo]) * t;
        out[1] = ys[lo] + (ys[hi] - ys[lo]) * t;
        out[2] = zs[lo] + (zs[hi] - zs[lo]) * t;
        float dYaw = ((yaws[hi] - yaws[lo]) % 360 + 540) % 360 - 180; // shortest way round
        out[3] = yaws[lo] + dYaw * t;
    }

    public long lastTime() {
        return size == 0 ? 0 : times[size - 1];
    }

    private void set(double[] out, int i) {
        out[0] = xs[i];
        out[1] = ys[i];
        out[2] = zs[i];
        out[3] = yaws[i];
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(trackId);
        out.writeLong(owner.getMostSignificantBits());
        out.writeLong(owner.getLeastSignificantBits());
        out.writeUTF(ownerName);
        out.writeUTF(world);
        out.writeBoolean(boat);
        out.writeLong(finishTime);
        out.writeUTF(skinValue == null ? "" : skinValue);
        out.writeUTF(skinSignature == null ? "" : skinSignature);
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeInt((int) times[i]);
            out.writeDouble(xs[i]);
            out.writeFloat((float) ys[i]);
            out.writeDouble(zs[i]);
            out.writeFloat(yaws[i]);
        }
    }

    /** Reads a recording; with headerOnly the path is skipped (used to index files at startup). */
    public static GhostRecording read(DataInputStream in, boolean headerOnly) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a ghost file");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unknown ghost file version " + version);
        }
        int trackId = in.readInt();
        UUID owner = new UUID(in.readLong(), in.readLong());
        String ownerName = in.readUTF();
        String world = in.readUTF();
        boolean boat = in.readBoolean();
        long finishTime = in.readLong();
        String skinValue = in.readUTF();
        String skinSignature = in.readUTF();
        int n = in.readInt();
        GhostRecording rec = new GhostRecording(trackId, owner, ownerName, world, boat, headerOnly ? 0 : n);
        rec.finishTime = finishTime;
        rec.skinValue = skinValue.isEmpty() ? null : skinValue;
        rec.skinSignature = skinSignature.isEmpty() ? null : skinSignature;
        if (!headerOnly) {
            for (int i = 0; i < n; i++) {
                rec.add(in.readInt(), in.readDouble(), in.readFloat(), in.readDouble(), in.readFloat());
            }
        }
        return rec;
    }
}
