package ru.ehtid.operator.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.snapshot.SnapshotIntegrity;
import ru.ehtid.operator.snapshot.StructureSnapshot;

/**
 * Separate snapshots from observer memory; never replace a snapshot in place.
 * Snapshots may include inventory NBT, so only server owners should access the files.
 */
public final class StructureSnapshotStorage {
    public static final int MAX_SNAPSHOTS_PER_PLAYER = 8;
    private static final int MAX_SNAPSHOT_FILE_BYTES = 4 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StructureSnapshotStorage() { }

    public static Path save(MinecraftServer server, StructureSnapshot snapshot) throws IOException {
        String owner = UUID.fromString(snapshot.playerUuid).toString();
        String snapshotId = UUID.fromString(snapshot.snapshotId).toString();
        if (!SnapshotIntegrity.valid(snapshot)) {
            throw new IOException("Snapshot checksum does not match its content");
        }

        Path directory = directory(server, owner);
        Files.createDirectories(directory);
        try (Stream<Path> files = Files.list(directory)) {
            long count = files.filter(StructureSnapshotStorage::isSnapshotFile).count();
            if (count >= MAX_SNAPSHOTS_PER_PLAYER) {
                throw new IOException("Snapshot limit reached (" + MAX_SNAPSHOTS_PER_PLAYER
                        + "); manually archive older snapshots before capturing another");
            }
        }

        Path destination = directory.resolve(snapshotId + ".json");
        if (Files.exists(destination)) throw new IOException("Snapshot already exists");
        Path temporary = Files.createTempFile(directory, "snapshot-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot, writer);
            }
            if (Files.size(temporary) > MAX_SNAPSHOT_FILE_BYTES) {
                throw new IOException("Snapshot JSON exceeds 4 MiB limit");
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static StructureSnapshot loadLatest(MinecraftServer server, String playerUuid)
            throws IOException {
        String owner = UUID.fromString(playerUuid).toString();
        Path directory = directory(server, owner);
        if (!Files.isDirectory(directory)) throw new IOException("No snapshots recorded");
        List<Path> candidates;
        try (Stream<Path> files = Files.list(directory)) {
            candidates = files.filter(StructureSnapshotStorage::isSnapshotFile)
                    .sorted(Comparator.comparing(StructureSnapshotStorage::modifiedTime).reversed()
                            .thenComparing(Path::toString))
                    .toList();
        }
        if (candidates.isEmpty()) throw new IOException("No snapshots recorded");
        for (Path candidate : candidates) {
            if (Files.size(candidate) > MAX_SNAPSHOT_FILE_BYTES) continue;
            try (Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                StructureSnapshot snapshot = GSON.fromJson(reader, StructureSnapshot.class);
                if (snapshot != null && owner.equals(snapshot.playerUuid)
                        && snapshot.formatVersion == 1
                        && snapshot.blocks != null && snapshot.blockCount() <= 4096
                        && SnapshotIntegrity.valid(snapshot)) {
                    return snapshot;
                }
            } catch (RuntimeException invalid) {
                // A damaged snapshot is not silently accepted.
            }
        }
        throw new IOException("No valid snapshot found; saved files were preserved for inspection");
    }

    private static long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isSnapshotFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString()
                .matches("[0-9a-f-]{36}\\.json");
    }

    private static Path directory(MinecraftServer server, String owner) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("snapshots").resolve(owner);
    }
}
