package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

        Map<RecoveryPlanner.Position, RecoveryPlanner.State> before = new HashMap<>();
        for (TransactionJournal.Cell cell : journal.source.before) {
            before.put(new RecoveryPlanner.Position(cell.x(), cell.y(), cell.z()),
                    RecoveryPlanner.State.of(cell));
        }
        List<Move> moves = new ArrayList<>();
        for (TransactionJournal.Cell cell : journal.source.before) {
            if (SafeBlockPalette.isOrdinaryAir(cell.blockId())) continue;
            RecoveryPlanner.Position src =
                    new RecoveryPlanner.Position(cell.x(), cell.y(), cell.z());
            RecoveryPlanner.Position dst = new RecoveryPlanner.Position(
                    Math.addExact(cell.x(), journal.offsetX),
                    Math.addExact(cell.y(), journal.offsetY),
                    Math.addExact(cell.z(), journal.offsetZ));
            moves.add(new Move(src, dst, RecoveryPlanner.State.of(cell)));
        }
        moves.sort(Comparator.comparingInt((Move move) -> move.from().x())
                .thenComparingInt(move -> move.from().y())
                .thenComparingInt(move -> move.from().z()));
        if (moves.isEmpty() || moves.size() * 2 > 256)
            return "Sandbox relocation refused: unsupported block count";

        RelocationExecutionLedger ledger = RelocationExecutionLedger.start(
                journal, moves.size() * 2, level.getGameTime());
        try {
            // This persisted state is the authorization that the mod truly
            // began an operation. The PREPARED journal alone never is.
            RelocationExecutionStorage.create(server, journal, ledger);
        } catch (IOException failure) {
            return "Sandbox relocation refused: cannot persist initial write-ahead ledger: "
                    + failure.getMessage();
        }

        // Stage 1: copy to the entirely empty target; Stage 2: clear source.
        // Every cell gets a durable intent BEFORE its setBlock and ACK AFTER.
        for (Move move : moves) {
            if (!write(server, level, journal, ledger, world, move.to(),
                    RecoveryPlanner.State.air(), move.value())) {
                return "Sandbox relocation INTERRUPTED at target; manual /operator sandbox rollback required";
            }
        }
        for (Move move : moves) {
            if (!write(server, level, journal, ledger, world, move.from(),
                    move.value(), RecoveryPlanner.State.air())) {
                return "Sandbox relocation INTERRUPTED at source; manual /operator sandbox rollback required";
            }
        }

        RecoveryPlanner.Plan verification = RecoveryPlanner.inspect(journal, world);
        if (verification.status() != RecoveryPlanner.Status.RESTORE_NEEDED
                || verification.appliedCells() != moves.size() * 2) {
            return "Sandbox relocation incomplete: verification failed; use /operator sandbox rollback";
        }

        try {
            ledger.setPhase("COMMITTED", level.getGameTime());
            RelocationExecutionStorage.update(server, journal, ledger);
        } catch (IOException | RuntimeException failure) {
            return "Sandbox relocation writes finished, but commit ledger was not persisted; "
                    + "use /operator sandbox rollback or inspect status";
        }
        return "Sandbox relocation COMMITTED: " + moves.size()
                + " plain blocks moved, " + ledger.appliedWrites
                + " guarded writes | transaction " + journal.journalId
                + " | /operator sandbox rollback can restore preimages"
                + " | this remains experimental; use a disposable world backup";
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

    private static boolean write(MinecraftServer server, ServerLevel level,
                                 TransactionJournal journal, RelocationExecutionLedger ledger,
                                 MinecraftSafeBlockWorld world, RecoveryPlanner.Position position,
                                 RecoveryPlanner.State expected, RecoveryPlanner.State next) {
        try {
            ledger.beginWrite("APPLY", position.x(), position.y(), position.z(), level.getGameTime());
            RelocationExecutionStorage.update(server, journal, ledger);
            if (!world.compareAndRestore(position, expected, next)) return false;
            ledger.acknowledgeWrite("APPLY", level.getGameTime());
            RelocationExecutionStorage.update(server, journal, ledger);
            return true;
        } catch (IOException | RuntimeException failure) {
            return false;
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

    private record Move(RecoveryPlanner.Position from, RecoveryPlanner.Position to,
                        RecoveryPlanner.State value) { }
}
