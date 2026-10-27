package com.voqii.timingghost;

import org.bukkit.Bukkit;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Ghost files live at ghosts/&lt;trackId&gt;/&lt;uuid&gt;.ghost, one per player per track: their best recorded run.
 * An in-memory index of finish times answers "is this a new best" and "who is fastest" without disk reads.
 * All file work runs in order on one background thread, so a read or reload never overtakes a pending save or delete.
 */
public final class GhostStore {

    private final TimingGhost plugin;
    private final Path root;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> new Thread(r, "TimingGhost-io"));
    private final Map<Integer, Map<UUID, Long>> index = new HashMap<>();
    private final Map<String, GhostRecording> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GhostRecording> eldest) {
            return size() > 64;
        }
    };

    public GhostStore(TimingGhost plugin) {
        this.plugin = plugin;
        this.root = plugin.getDataFolder().toPath().resolve("ghosts");
    }

    /** Waits for queued saves and deletes to reach the disk. */
    public void flush() {
        try {
            io.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Timed out waiting for ghost files to be written");
        }
    }

    /** Finishes queued file work; call on disable so a ghost saved just before shutdown isn't lost. */
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Some ghost files weren't written before shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void loadIndex() {
        index.clear();
        cache.clear();
        if (!Files.isDirectory(root)) {
            return;
        }
        int count = 0;
        try (Stream<Path> files = Files.walk(root, 2)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".ghost"))::iterator) {
                try {
                    GhostRecording header = readFile(file, true);
                    index.computeIfAbsent(header.trackId, k -> new HashMap<>()).put(header.owner, header.finishTime);
                    count++;
                } catch (IOException e) {
                    plugin.getLogger().warning("Skipping unreadable ghost " + root.relativize(file) + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't list ghosts", e);
        }
        plugin.getLogger().info("Loaded " + count + " ghost(s).");
    }

    public Long bestTime(int trackId, UUID owner) {
        Map<UUID, Long> times = index.get(trackId);
        return times == null ? null : times.get(owner);
    }

    public UUID fastestOwner(int trackId) {
        Map<UUID, Long> times = index.get(trackId);
        if (times == null) {
            return null;
        }
        return times.entrySet().stream().min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** Loads a ghost off the main thread; completes with null when there isn't one. Callbacks run on the main thread. */
    public CompletableFuture<GhostRecording> load(int trackId, UUID owner) {
        CompletableFuture<GhostRecording> result = new CompletableFuture<>();
        if (bestTime(trackId, owner) == null) {
            result.complete(null);
            return result;
        }
        GhostRecording cached = cache.get(key(trackId, owner));
        if (cached != null) {
            result.complete(cached);
            return result;
        }
        Path file = file(trackId, owner);
        io.execute(() -> {
            GhostRecording rec = null;
            try {
                rec = readFile(file, false);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't read ghost " + root.relativize(file) + ": " + e.getMessage());
            }
            GhostRecording loaded = rec;
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (loaded != null) {
                    cache.put(key(trackId, owner), loaded);
                }
                result.complete(loaded);
            });
        });
        return result;
    }

    /** Saves a finished run. Serialised here on the main thread, written to disk asynchronously. */
    public void save(GhostRecording rec) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(bytes)))) {
            rec.write(out);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't encode ghost", e);
            return;
        }
        index.computeIfAbsent(rec.trackId, k -> new HashMap<>()).put(rec.owner, rec.finishTime);
        cache.put(key(rec.trackId, rec.owner), rec);
        Path file = file(rec.trackId, rec.owner);
        byte[] data = bytes.toByteArray();
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't save ghost " + root.relativize(file), e);
            }
        });
    }

    /** Deletes one player's ghost on a track, or every ghost on it when owner is null. Returns how many went. */
    public int delete(int trackId, UUID owner) {
        Map<UUID, Long> times = index.get(trackId);
        if (times == null) {
            return 0;
        }
        List<UUID> owners = owner == null ? new ArrayList<>(times.keySet()) : List.of(owner);
        int removed = 0;
        for (UUID uuid : owners) {
            if (times.remove(uuid) != null) {
                removed++;
                cache.remove(key(trackId, uuid));
                Path file = file(trackId, uuid);
                io.execute(() -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        plugin.getLogger().warning("Couldn't delete " + root.relativize(file) + ": " + e.getMessage());
                    }
                });
            }
        }
        return removed;
    }

    private Path file(int trackId, UUID owner) {
        return root.resolve(Integer.toString(trackId)).resolve(owner + ".ghost");
    }

    private static String key(int trackId, UUID owner) {
        return trackId + "/" + owner;
    }

    private static GhostRecording readFile(Path file, boolean headerOnly) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            return GhostRecording.read(in, headerOnly);
        }
    }
}
