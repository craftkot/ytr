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
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.transaction.RecoveryCheckpointLedger;
import ru.ehtid.operator.transaction.RecoveryPlanner;
import ru.ehtid.operator.transaction.TransactionJournal;

/**
 * Server-local, atomic checkpoint file for read-only recovery audits.
 *
 * No mutation authorization or world write methods are exposed. A damaged
 * existing checkpoint is never overwritten or silently recreated.
 */
public final class RecoveryCheckpointStorage {
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecoveryCheckpointStorage() { }

    /** Persist an initial PREPARED checkpoint after the journal itself is durable. */
    public static RecoveryCheckpointLedger initialize(MinecraftServer server,
                                                      TransactionJournal journal)
            throws IOException {
        Path path = path(server, journal);
        if (Files.exists(path)) return loadVerified(server, journal);
        RecoveryCheckpointLedger ledger =
                RecoveryCheckpointLedger.initialize(journal, journal.capturedAtTick);
        saveVerified(server, journal, ledger, false);
        return ledger;
    }

    /** Read the exact journal's sidecar. Never choose a different historical journal. */
    public static RecoveryCheckpointLedger loadVerified(MinecraftServer server,
                                                         TransactionJournal journal)
            throws IOException {
        Path path = path(server, journal);
        if (!Files.isRegularFile(path)) throw new IOException("Recovery checkpoint file not found");
        if (Files.size(path) > MAX_FILE_BYTES)
            throw new IOException("Recovery checkpoint exceeds 1 MiB");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            RecoveryCheckpointLedger ledger = GSON.fromJson(reader, RecoveryCheckpointLedger.class);
            if (ledger == null || !ledger.valid() || !ledger.matches(journal))
                throw new IOException("Recovery checkpoint checksum or journal binding mismatch");
            return ledger;
        } catch (RuntimeException invalid) {
            throw new IOException("Recovery checkpoint is damaged", invalid);
        }
    }

    /**
     * Record an audit as a full hash-chained replacement. Server commands run
     * on the main thread, so concurrent writers within this mod are serialized.
     */
    public static RecoveryCheckpointLedger appendAudit(MinecraftServer server,
                                                        TransactionJournal journal,
                                                        long gameTick,
                                                        RecoveryPlanner.Plan plan)
            throws IOException {
        RecoveryCheckpointLedger ledger;
        if (Files.exists(path(server, journal))) {
            ledger = loadVerified(server, journal);
        } else {
            // Supports journals created in earlier versions without sidecars.
            ledger = initialize(server, journal);
        }
        try {
            ledger.appendAudit(journal, gameTick, plan);
        } catch (RuntimeException invalid) {
            throw new IOException("Audit checkpoint refused: " + invalid.getMessage(), invalid);
        }
        saveVerified(server, journal, ledger, true);
        return ledger;
    }

    private static void saveVerified(MinecraftServer server, TransactionJournal journal,
                                     RecoveryCheckpointLedger ledger, boolean replace)
            throws IOException {
        if (!ledger.valid() || !ledger.matches(journal))
            throw new IOException("Cannot persist invalid recovery checkpoint");
        Path destination = path(server, journal);
        Files.createDirectories(destination.getParent());
        if (!replace && Files.exists(destination)) {
            throw new IOException("Recovery checkpoint already exists");
        }
        if (replace && !Files.isRegularFile(destination))
            throw new IOException("Recovery checkpoint disappeared during update");

        Path temporary = Files.createTempFile(destination.getParent(), "recovery-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(ledger, writer);
            }
            if (Files.size(temporary) > MAX_FILE_BYTES)
                throw new IOException("Recovery checkpoint exceeds 1 MiB");

            try {
                if (replace) {
                    Files.move(temporary, destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (replace) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, destination);
                }
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
                    .resolve("operator_reality").resolve("recovery").resolve(owner)
                    .resolve(id + ".json");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid journal owner or ID", invalid);
        }
    }
}
