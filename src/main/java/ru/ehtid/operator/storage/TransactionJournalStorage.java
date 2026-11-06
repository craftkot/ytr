package ru.ehtid.operator.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.transaction.TransactionIntegrity;
import ru.ehtid.operator.transaction.TransactionJournal;

/**
 * Durable, opt-in PREPARED journal storage. Journals contain private world
 * state and inventory data; never include them in command replies or telemetry.
 */
public final class TransactionJournalStorage {
    public static final int MAX_JOURNALS_PER_PLAYER = 4;
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TransactionJournalStorage() { }

    public static Path save(MinecraftServer server, TransactionJournal journal)
            throws IOException {
        String owner = UUID.fromString(journal.ownerUuid).toString();
        return saveToDirectory(directory(server, owner), journal);
    }

    /** Package-private so storage durability can be tested without a Minecraft server. */
    static Path saveToDirectory(Path directory, TransactionJournal journal) throws IOException {
        String owner = UUID.fromString(journal.ownerUuid).toString();
        String journalId = UUID.fromString(journal.journalId).toString();
        if (!"PREPARED".equals(journal.phase) || journal.formatVersion != 1
                || !TransactionIntegrity.valid(journal))
            throw new IOException("Invalid or non-PREPARED transaction journal");

        Files.createDirectories(directory);
        try (Stream<Path> files = Files.list(directory)) {
            if (files.filter(TransactionJournalStorage::isJournalFile).count()
                    >= MAX_JOURNALS_PER_PLAYER)
                throw new IOException("Journal limit reached; archive old journal files manually");
        }

        Path destination = directory.resolve(journalId + ".json");
        if (Files.exists(destination)) throw new IOException("Journal already exists");
        Path temporary = Files.createTempFile(directory, "transaction-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(journal, writer);
            }
            if (Files.size(temporary) > MAX_FILE_BYTES)
                throw new IOException("Journal exceeds the 8 MiB file limit");
            // Persist file contents before making the journal discoverable.
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            // Persist the directory entry where supported (Windows/filesystem dependent).
            try (FileChannel directoryChannel = FileChannel.open(directory,
                    StandardOpenOption.READ)) {
                directoryChannel.force(true);
            } catch (IOException | UnsupportedOperationException unsupportedDirectoryFsync) {
                // File content is still fsynced; some platforms cannot fsync directories.
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static TransactionJournal load(MinecraftServer server, String playerUuid,
                                                String journalId) throws IOException {
        String owner = UUID.fromString(playerUuid).toString();
        String id = UUID.fromString(journalId).toString();
        Path file = directory(server, owner).resolve(id + ".json");
        return read(file, owner, id);
    }

    public static TransactionJournal loadLatest(MinecraftServer server, String playerUuid)
            throws IOException {
        String owner = UUID.fromString(playerUuid).toString();
        Path directory = directory(server, owner);
        if (!Files.isDirectory(directory)) throw new IOException("No prepared journals");
        List<Path> candidates;
        try (Stream<Path> files = Files.list(directory)) {
            candidates = files.filter(TransactionJournalStorage::isJournalFile)
                    .sorted(Comparator.comparing(TransactionJournalStorage::modifiedTime)
                            .reversed().thenComparing(Path::toString))
                    .toList();
        }
        if (candidates.isEmpty()) throw new IOException("No prepared journals");
        // Fail closed if the newest journal is damaged. Silently falling back
        // to an older journal could restore the wrong historical world state.
        Path latest = candidates.get(0);
        if (Files.size(latest) > MAX_FILE_BYTES) {
            throw new IOException("Latest journal exceeds size limit; no fallback allowed");
        }
        String latestId = latest.getFileName().toString().replaceFirst("\\.json$", "");
        try {
            return read(latest, owner, latestId);
        } catch (IOException invalid) {
            throw new IOException("Latest journal failed integrity checks; no fallback allowed",
                    invalid);
        }
    }

    static TransactionJournal read(Path file, String owner, String id)
            throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Prepared journal not found");
        if (Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Journal exceeds size limit");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            TransactionJournal journal = GSON.fromJson(reader, TransactionJournal.class);
            if (journal != null && owner.equals(journal.ownerUuid)
                    && (id == null || id.equals(journal.journalId))
                    && "PREPARED".equals(journal.phase) && journal.formatVersion == 1
                    && TransactionIntegrity.valid(journal)) return journal;
        } catch (RuntimeException invalid) {
            throw new IOException("Prepared journal is malformed", invalid);
        }
        throw new IOException("Prepared journal failed integrity checks");
    }

    private static long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static boolean isJournalFile(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().matches("[0-9a-f-]{36}\\.json");
    }

    private static Path directory(MinecraftServer server, String owner) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions").resolve(owner);
    }
}
