package ru.ehtid.operator.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.transaction.RelocationExecutionLedger;
import ru.ehtid.operator.transaction.TransactionJournal;

/**
 * fsync-backed before/after write intents for a small, explicit relocation.
 * A bad or missing ledger NEVER falls back to another transaction.
 */
public final class RelocationExecutionStorage {
    public static final int MAX_FILE_BYTES = 16 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RelocationExecutionStorage() { }

    public static boolean exists(MinecraftServer server, TransactionJournal journal)
            throws IOException {
        return Files.exists(path(server, journal));
    }

    public static void create(MinecraftServer server, TransactionJournal journal,
                              RelocationExecutionLedger ledger) throws IOException {
        save(path(server, journal), journal, ledger, false);
    }

    public static void update(MinecraftServer server, TransactionJournal journal,
                              RelocationExecutionLedger ledger) throws IOException {
        save(path(server, journal), journal, ledger, true);
    }

    public static RelocationExecutionLedger load(MinecraftServer server,
                                                  TransactionJournal journal)
            throws IOException {
        return read(path(server, journal), journal);
    }

    /** For JUnit crash/restart tests, no Minecraft runtime required. */
    static RelocationExecutionLedger read(Path path, TransactionJournal journal)
            throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES)
            throw new IOException("Execution ledger missing or oversized");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RelocationExecutionLedger result =
                    GSON.fromJson(reader, RelocationExecutionLedger.class);
            if (result == null || !result.matches(journal))
                throw new IOException("Execution ledger checksum or ownership mismatch");
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException("Execution ledger is damaged", invalid);
        }
    }

    static void save(Path destination, TransactionJournal journal,
                     RelocationExecutionLedger ledger, boolean replace) throws IOException {
        if (!ledger.matches(journal)) throw new IOException("Invalid execution state");
        Files.createDirectories(destination.getParent());
        if (replace ? !Files.isRegularFile(destination) : Files.exists(destination))
            throw new IOException("Execution ledger existence/replace mismatch");

        Path temporary = Files.createTempFile(destination.getParent(), "execution-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(ledger, writer);
            }
            if (Files.size(temporary) > MAX_FILE_BYTES) {
                throw new IOException("Execution ledger exceeds 16 KiB");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, destination,
                        replace
                                ? new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING}
                                : new StandardCopyOption[]{StandardCopyOption.ATOMIC_MOVE});
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (replace) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, destination);
                }
            }
            // Best-effort directory metadata durability for journal discovery after a crash.
            try (FileChannel directory = FileChannel.open(destination.getParent(),
                    StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException unsupportedDirectoryFsync) {
                // Some filesystems/Windows do not permit fsync of a directory.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path path(MinecraftServer server, TransactionJournal journal)
            throws IOException {
        try {
            String owner = UUID.fromString(journal.ownerUuid).toString();
            String id = UUID.fromString(journal.journalId).toString();
            return server.getWorldPath(LevelResource.ROOT)
                    .resolve("operator_reality").resolve("executions").resolve(owner)
                    .resolve(id + ".json");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Bad execution journal identifiers", invalid);
        }
    }
}
