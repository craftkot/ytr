package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import ru.ehtid.operator.snapshot.StructureSnapshot;
import ru.ehtid.operator.storage.StructureSnapshotStorage;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Manually invoked, non-automatic, very small proof-of-concept relocation.
 *
 * Only simple vanilla default BlockState blocks may be changed. BlockEntity
 * inventory/metadata, fluids, power, gravity, attached blocks and entities are
 * refused. All changes occur synchronously on the command's server thread.
 * World/chunk saves are NOT a cross-chunk atomic database transaction.
 */
public final class ExperimentalRelocationService {
    public static final int MAX_REGION_CELLS = 128;

    private ExperimentalRelocationService() { }

    public static String apply(MinecraftServer server, ServerPlayer player) {
        TransactionJournal journal;
        try {
            journal = TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
            if (RelocationExecutionStorage.exists(server, journal)) {
                return "Sandbox relocation refused: execution ledger already exists; inspect or rollback it";
            }
        } catch (IOException problem) {
            return "Sandbox relocation refused: " + problem.getMessage();
        }

        ServerLevel level = (ServerLevel) player.level();
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        String validation = validate(journal, server, player, level, world);
        if (validation != null) return "Sandbox relocation refused: " + validation;

        SandboxTransactionEngine.Result transfer = SandboxTransactionEngine.apply(
                journal, world, level::getGameTime, new SandboxTransactionEngine.LedgerStore() {
                    @Override
                    public void create(TransactionJournal transaction,
                                       RelocationExecutionLedger state) throws IOException {
                        RelocationExecutionStorage.create(server, transaction, state);
                    }

                    @Override
                    public void update(TransactionJournal transaction,
                                       RelocationExecutionLedger state) throws IOException {
                        RelocationExecutionStorage.update(server, transaction, state);
                    }
                });

        if (!transfer.committed()) {
            return "Sandbox relocation " + transfer.outcome()
                    + ": " + transfer.reason()
                    + " | WAL-confirmed writes: " + transfer.confirmedWrites()
                    + " | if an execution ledger exists, only manual rollback is allowed";
        }
        return "Sandbox relocation COMMITTED: " + transfer.transferredBlocks()
                + " plain blocks moved, " + transfer.confirmedWrites()
                + " guarded writes | transaction " + journal.journalId
                + " | /operator sandbox rollback can restore preimages"
                + " | experimental: use only a disposable world backup";
    }

    public static String rollback(MinecraftServer server, ServerPlayer player) {
        TransactionJournal journal;
        RelocationExecutionLedger ledger;
        try {
            journal = TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
            // No ledger means we have no evidence this mod ever modified blocks.
            ledger = RelocationExecutionStorage.load(server, journal);
        } catch (IOException failure) {
            return "Sandbox rollback refused: " + failure.getMessage();
        }

        if ("ROLLED_BACK".equals(ledger.phase))
            return "Sandbox transaction already ROLLED_BACK: " + journal.journalId;
        if (!SafeBlockPalette.accepts(journal))
            return "Sandbox rollback refused: unsupported or altered journal block palette";

        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().toString().equals(journal.dimension))
            return "Sandbox rollback refused: wrong dimension";
        if (hasEntities(level, journal.source) || hasEntities(level, journal.target))
            return "Sandbox rollback refused: entities inside affected region";

        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        RecoveryPlanner.Plan preflight = RecoveryPlanner.inspect(journal, world);
        if (!preflight.safeToRestore())
            return "Sandbox rollback refused: " + preflight.status()
                    + " (foreign changes or unloaded cells must not be overwritten)";

        try {
            if ("COMMITTED".equals(ledger.phase)) {
                ledger.setPhase("ROLLING_BACK", level.getGameTime());
            } else if ("APPLYING".equals(ledger.phase) || "ROLLING_BACK".equals(ledger.phase)) {
                // An unacknowledged pending write is possible after a crash.
                // Re-read every world cell above; it is safe to resume only
                // when all states agree with BEFORE or predicted AFTER.
                ledger.beginRollbackAfterInterruptedWrite(level.getGameTime());
            } else {
                return "Sandbox rollback refused: invalid execution phase";
            }
            RelocationExecutionStorage.update(server, journal, ledger);
        } catch (IOException | RuntimeException failure) {
            return "Sandbox rollback refused: cannot persist recovery phase: " + failure.getMessage();
        }

        RecoveryExecutor.CheckpointSink sink = new RecoveryExecutor.CheckpointSink() {
            @Override
            public void beforeWrite(int sequence, RecoveryPlanner.Change change) {
                ledger.beginWrite("RESTORE", change.position().x(),
                        change.position().y(), change.position().z(), level.getGameTime());
                persist();
            }

            @Override
            public void afterWrite(int sequence, RecoveryPlanner.Change change) {
                ledger.acknowledgeWrite("RESTORE", level.getGameTime());
                persist();
            }

            private void persist() {
                try {
                    RelocationExecutionStorage.update(server, journal, ledger);
                } catch (IOException problem) {
                    throw new UncheckedIOException(problem);
                }
            }
        };

        RecoveryExecutor.Result result = RecoveryExecutor.restore(journal, world, sink);
        if (result.outcome() == RecoveryExecutor.Outcome.COMPLETED
                || result.outcome() == RecoveryExecutor.Outcome.NOTHING_TO_RESTORE) {
            try {
                ledger.setPhase("ROLLED_BACK", level.getGameTime());
                RelocationExecutionStorage.update(server, journal, ledger);
                return "Sandbox rollback ROLLED_BACK | restored " + result.restored()
                        + " blocks | original regions verified | journal " + journal.journalId;
            } catch (IOException | RuntimeException failure) {
                return "Sandbox blocks restored, but rollback completion could not be saved; "
                        + "retry /operator sandbox rollback";
            }
        }
        return "Sandbox rollback " + result.outcome() + ": " + result.reason()
                + " | restored " + result.restored()
                + " | retry only after resolving conflicts, without deleting the journal";
    }

    public static String status(MinecraftServer server, ServerPlayer player) {
        try {
            TransactionJournal journal =
                    TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
            if (!RelocationExecutionStorage.exists(server, journal)) {
                return "Sandbox execution not started for latest PREPARED journal";
            }
            RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
            return "Sandbox execution " + ledger.phase + " | journal " + ledger.journalId
                    + " | applied writes: " + ledger.appliedWrites + "/" + ledger.totalWrites
                    + " | restored writes: " + ledger.restoredWrites
                    + " | pending: " + (ledger.pendingOperation.isEmpty()
                            ? "none" : ledger.pendingOperation + "@"
                                    + ledger.pendingX + "," + ledger.pendingY + "," + ledger.pendingZ)
                    + " | no autonomous mutation";
        } catch (IOException failure) {
            return "Sandbox execution ledger unavailable: " + failure.getMessage();
        }
    }

    private static String validate(TransactionJournal journal, MinecraftServer server,
                                   ServerPlayer player, ServerLevel level,
                                   MinecraftSafeBlockWorld world) {
        if (!level.dimension().toString().equals(journal.dimension))
            return "wrong dimension";
        if (journal.source.volume() > MAX_REGION_CELLS
                || journal.target.volume() > MAX_REGION_CELLS)
            return "only up to 128 cells per region are permitted";
        if (!SafeBlockPalette.accepts(journal) || journal.blockEntityCount != 0)
            return "contains block entities, unsupported blocks or non-default states";
        if ((long) journal.source.minX + journal.offsetX != journal.target.minX
                || (long) journal.source.minY + journal.offsetY != journal.target.minY
                || (long) journal.source.minZ + journal.offsetZ != journal.target.minZ)
            return "journal offsets do not match";
        if (hasEntities(level, journal.source) || hasEntities(level, journal.target))
            return "entities or players inside source/destination";
        try {
            StructureSnapshot snapshot =
                    StructureSnapshotStorage.loadLatest(server, player.getUUID().toString());
            if (!snapshot.snapshotId.equals(journal.sourceSnapshotId)
                    || !snapshot.sha256.equals(journal.sourceSnapshotSha256))
                return "latest construction snapshot does not match journal";
            Map<RecoveryPlanner.Position, RecoveryPlanner.State> recorded = new HashMap<>();
            for (StructureSnapshot.Entry block : snapshot.blocks) {
                if (block.blockEntitySnbt() != null)
                    return "snapshot contains block entities";
                recorded.put(new RecoveryPlanner.Position(block.x(), block.y(), block.z()),
                        new RecoveryPlanner.State(block.blockId(), block.blockState(), null));
            }
            int nonAir = 0;
            for (TransactionJournal.Cell cell : journal.source.before) {
                if (SafeBlockPalette.isOrdinaryAir(cell.blockId())) continue;
                nonAir++;
                RecoveryPlanner.State observed = recorded.get(
                        new RecoveryPlanner.Position(cell.x(), cell.y(), cell.z()));
                if (observed == null || !observed.equals(RecoveryPlanner.State.of(cell)))
                    return "source contains untracked natural or modified blocks";
            }
            if (nonAir < 24 || nonAir != recorded.size())
                return "source must contain the complete player-recorded building (24+ blocks)";
        } catch (IOException | RuntimeException exception) {
            return "cannot validate construction snapshot: " + exception.getMessage();
        }
        for (TransactionJournal.Cell cell : journal.target.before) {
            if (!"minecraft:air".equals(cell.blockId())) return "destination was not fully air";
        }
        RecoveryPlanner.Plan checked = RecoveryPlanner.inspect(journal, world);
        if (checked.status() != RecoveryPlanner.Status.UNCHANGED)
            return "current world differs from original: " + checked.status();

        // Strict environment gate. Unreadable/unknown neighbors are not treated
        // as proof of safe relocation.
        RecoveryBoundaryInspector.Assessment boundary =
                RecoveryBoundaryInspector.inspect(level, journal);
        if (!"NO_LISTED_HAZARDS".equals(boundary.status()))
            return "neighboring blocks need review: " + boundary.summary();
        return null;
    }

    private static boolean hasEntities(ServerLevel level, TransactionJournal.Region region) {
        AABB box = new AABB(region.minX, region.minY, region.minZ,
                (double) region.maxX + 1.0D,
                (double) region.maxY + 1.0D,
                (double) region.maxZ + 1.0D);
        return !level.getEntities(null, box).isEmpty();
    }


}
