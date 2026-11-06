package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Real apply algorithm shared by Minecraft and deterministic fault-injection tests.
 * The caller must separately check owner permissions, snapshot identity, entities,
 * neighboring blocks and world dimension before constructing its world adapter.
 *
 * Every mutation has a persisted INTENT before the compare-and-write and a
 * persisted ACK afterward. This DOES NOT make Minecraft chunk storage atomic.
 */
public final class SandboxTransactionEngine {
    public static final int MAX_CELLS_PER_REGION = 128;
    public static final int MIN_TRACKED_BLOCKS = 24;

    private SandboxTransactionEngine() { }

    public interface LedgerStore {
        void create(TransactionJournal journal, RelocationExecutionLedger state) throws IOException;
        void update(TransactionJournal journal, RelocationExecutionLedger state) throws IOException;

        /** Flush the affected Minecraft world state before a WAL terminal phase is durable. */
        void flushWorld() throws IOException;
    }

    public enum Outcome {
        REFUSED, INTERRUPTED, COMMITTED, ROLLED_BACK
    }

    public record Result(Outcome outcome, String reason, int transferredBlocks,
                         int confirmedWrites) {
        public boolean committed() { return outcome == Outcome.COMMITTED; }
    }

    public static Result apply(TransactionJournal journal,
                               RecoveryExecutor.MutableWorld world,
                               LongSupplier gameTick, LedgerStore store) {
        if (journal == null || world == null || gameTick == null || store == null
                || !SafeBlockPalette.accepts(journal)
                || !validLayout(journal)) {
            return new Result(Outcome.REFUSED, "INVALID_JOURNAL_OR_LAYOUT", 0, 0);
        }

        List<Mutation> changes = buildMoves(journal);
        int count = changes.size() / 2;
        if (count < MIN_TRACKED_BLOCKS || changes.size() > 256) {
            return new Result(Outcome.REFUSED, "UNSUPPORTED_SIZE", 0, 0);
        }
        RecoveryPlanner.Plan before = RecoveryPlanner.inspect(journal, world);
        if (before.status() != RecoveryPlanner.Status.UNCHANGED) {
            return new Result(Outcome.REFUSED, "PRECHECK_" + before.status(), 0, 0);
        }

        RelocationExecutionLedger ledger =
                RelocationExecutionLedger.start(journal, changes.size(), gameTick.getAsLong());
        try {
            // No world edits can occur before this succeeds.
            store.create(journal, ledger);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.REFUSED, "WAL_CREATE_FAILED", count, 0);
        }

        int acknowledged = 0;
        for (Mutation mutation : changes) {
            try {
                ledger.beginWrite("APPLY",
                        mutation.position.x(), mutation.position.y(), mutation.position.z(),
                        gameTick.getAsLong());
                store.update(journal, ledger);
                if (!world.compareAndRestore(
                        mutation.position, mutation.before, mutation.after)) {
                    return new Result(Outcome.INTERRUPTED, "WORLD_CHANGED_OR_WRITE_FAILED",
                            count, acknowledged);
                }
                ledger.acknowledgeWrite("APPLY", gameTick.getAsLong());
                store.update(journal, ledger);
                acknowledged++;
            } catch (IOException | RuntimeException failure) {
                // May have written a block before its acknowledgement. This
                // operation must never be retried as APPLY, only audited/rolled back.
                return new Result(Outcome.INTERRUPTED, "WRITE_OR_WAL_INTERRUPTED",
                        count, acknowledged);
            }
        }
        RecoveryPlanner.Plan after;
        try {
            after = RecoveryPlanner.inspect(journal, world);
        } catch (RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "POSTCHECK_READ_FAILED", count, acknowledged);
        }
        if (after.status() != RecoveryPlanner.Status.RESTORE_NEEDED
                || after.appliedCells() != changes.size()) {
            return new Result(Outcome.INTERRUPTED, "POSTCHECK_MISMATCH", count, acknowledged);
        }

        try {
            // The terminal WAL state must never get ahead of the chunk data.
            store.flushWorld();
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "WORLD_FLUSH_FAILED", count, acknowledged);
        }
        try {
            ledger.setPhase("COMMITTED", gameTick.getAsLong());
            store.update(journal, ledger);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "WAL_COMMIT_FAILED", count, acknowledged);
        }
        return new Result(Outcome.COMMITTED, "VERIFIED", count, acknowledged);
    }

    /**
     * Manual recovery for an operation previously started BY THIS MOD. Unlike
     * an audit checkpoint, the execution ledger proves an opt-in mutation
     * began and its current phase. In-game entity/neighbor checks remain the
     * caller's responsibility and cannot be skipped.
     */
    public static Result rollback(TransactionJournal journal,
                                  RelocationExecutionLedger execution,
                                  RecoveryExecutor.MutableWorld world,
                                  LongSupplier gameTick, LedgerStore store) {
        if (journal == null || execution == null || world == null
                || gameTick == null || store == null
                || !SafeBlockPalette.accepts(journal) || !validLayout(journal)
                || !execution.matches(journal)) {
            return new Result(Outcome.REFUSED, "INVALID_EXECUTION_OR_JOURNAL", 0, 0);
        }
        if ("ROLLED_BACK".equals(execution.phase)) {
            return new Result(Outcome.ROLLED_BACK, "ALREADY_ROLLED_BACK", 0, 0);
        }
        RecoveryPlanner.Plan before;
        try {
            before = RecoveryPlanner.inspect(journal, world);
        } catch (RuntimeException failure) {
            return new Result(Outcome.REFUSED, "PRECHECK_READ_FAILED", 0, 0);
        }
        if (!before.safeToRestore()) {
            return new Result(Outcome.REFUSED,
                    "PRECHECK_" + before.status(), 0, 0);
        }

        boolean pendingRestoreAlreadyApplied = false;
        if ("RESTORE".equals(execution.pendingOperation)) {
            RecoveryPlanner.Position pending = new RecoveryPlanner.Position(
                    execution.pendingX, execution.pendingY, execution.pendingZ);
            boolean belongsToJournal = containsCell(journal.source.before, pending)
                    || containsCell(journal.target.before, pending);
            if (!belongsToJournal) {
                return new Result(Outcome.REFUSED, "INVALID_PENDING_RESTORE_POSITION", 0, 0);
            }
            // A pending restore absent from the current change list already
            // matches its BEFORE image: the world write survived, its ACK did not.
            pendingRestoreAlreadyApplied = before.changes().stream()
                    .noneMatch(change -> change.position().equals(pending));
        }

        try {
            if ("COMMITTED".equals(execution.phase)) {
                execution.setPhase("ROLLING_BACK", gameTick.getAsLong());
            } else if ("APPLYING".equals(execution.phase)
                    || "ROLLING_BACK".equals(execution.phase)) {
                // Reconcile the interrupted intent only after full BEFORE/AFTER
                // world preflight; an already-restored pending cell gets its ACK
                // accounted for, otherwise it remains in the restore plan.
                execution.beginRollbackAfterInterruptedWrite(
                        gameTick.getAsLong(), pendingRestoreAlreadyApplied);
            } else {
                return new Result(Outcome.REFUSED, "INVALID_PHASE", 0, 0);
            }
            store.update(journal, execution);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.REFUSED, "WAL_ROLLBACK_PHASE_FAILED", 0, 0);
        }

        RecoveryExecutor.CheckpointSink sink = new RecoveryExecutor.CheckpointSink() {
            @Override
            public void beforeWrite(int sequence, RecoveryPlanner.Change change) {
                execution.beginWrite("RESTORE", change.position().x(),
                        change.position().y(), change.position().z(), gameTick.getAsLong());
                persist();
            }

            @Override
            public void afterWrite(int sequence, RecoveryPlanner.Change change) {
                execution.acknowledgeWrite("RESTORE", gameTick.getAsLong());
                persist();
            }

            private void persist() {
                try {
                    store.update(journal, execution);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }
        };
        RecoveryExecutor.Result restored = RecoveryExecutor.restore(journal, world, sink);
        if (restored.outcome() != RecoveryExecutor.Outcome.COMPLETED
                && restored.outcome() != RecoveryExecutor.Outcome.NOTHING_TO_RESTORE) {
            return new Result(Outcome.INTERRUPTED,
                    "RECOVERY_" + restored.outcome() + "_" + restored.reason(),
                    0, restored.restored());
        }
        RecoveryPlanner.Plan postRestore;
        try {
            postRestore = RecoveryPlanner.inspect(journal, world);
        } catch (RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "RECOVERY_POSTCHECK_READ_FAILED",
                    0, restored.restored());
        }
        if (postRestore.status() != RecoveryPlanner.Status.UNCHANGED) {
            return new Result(Outcome.INTERRUPTED, "RECOVERY_POSTCHECK_MISMATCH",
                    0, restored.restored());
        }
        try {
            // Persist the restored chunk state BEFORE declaring ROLLED_BACK.
            store.flushWorld();
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "WORLD_FLUSH_FAILED",
                    0, restored.restored());
        }
        try {
            execution.setPhase("ROLLED_BACK", gameTick.getAsLong());
            store.update(journal, execution);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "WAL_ROLLBACK_COMMIT_FAILED",
                    0, restored.restored());
        }
        return new Result(Outcome.ROLLED_BACK, "VERIFIED",
                0, restored.restored());
    }

    private static boolean containsCell(List<TransactionJournal.Cell> cells,
                                        RecoveryPlanner.Position position) {
        for (TransactionJournal.Cell cell : cells) {
            if (cell.x() == position.x() && cell.y() == position.y()
                    && cell.z() == position.z()) return true;
        }
        return false;
    }

    private static boolean validLayout(TransactionJournal journal) {
        if (journal.source.volume() <= 0 || journal.target.volume() <= 0
                || journal.source.volume() > MAX_CELLS_PER_REGION
                || journal.target.volume() > MAX_CELLS_PER_REGION
                || journal.blockEntityCount != 0 || journal.totalNbtBytes != 0
                || (long) journal.source.minX + journal.offsetX != journal.target.minX
                || (long) journal.source.minY + journal.offsetY != journal.target.minY
                || (long) journal.source.minZ + journal.offsetZ != journal.target.minZ
                || (long) journal.source.maxX + journal.offsetX != journal.target.maxX
                || (long) journal.source.maxY + journal.offsetY != journal.target.maxY
                || (long) journal.source.maxZ + journal.offsetZ != journal.target.maxZ)
            return false;
        for (TransactionJournal.Cell target : journal.target.before) {
            if (!"minecraft:air".equals(target.blockId())) return false;
        }
        return true;
    }

    private static List<Mutation> buildMoves(TransactionJournal journal) {
        List<Mutation> targetWrites = new ArrayList<>();
        List<Mutation> sourceClears = new ArrayList<>();
        RecoveryPlanner.State air = RecoveryPlanner.State.air();
        for (TransactionJournal.Cell block : journal.source.before) {
            if (SafeBlockPalette.isOrdinaryAir(block.blockId())) continue;
            RecoveryPlanner.Position from =
                    new RecoveryPlanner.Position(block.x(), block.y(), block.z());
            RecoveryPlanner.Position to = new RecoveryPlanner.Position(
                    Math.addExact(block.x(), journal.offsetX),
                    Math.addExact(block.y(), journal.offsetY),
                    Math.addExact(block.z(), journal.offsetZ));
            RecoveryPlanner.State state = RecoveryPlanner.State.of(block);
            targetWrites.add(new Mutation(to, air, state));
            sourceClears.add(new Mutation(from, state, air));
        }
        Comparator<Mutation> order = Comparator
                .comparingInt((Mutation m) -> m.position.x())
                .thenComparingInt(m -> m.position.y())
                .thenComparingInt(m -> m.position.z());
        targetWrites.sort(order);
        sourceClears.sort(order);
        List<Mutation> result = new ArrayList<>(targetWrites.size() + sourceClears.size());
        result.addAll(targetWrites); // Copy before erasing any source cells.
        result.addAll(sourceClears);
        return List.copyOf(result);
    }

    private record Mutation(RecoveryPlanner.Position position,
                            RecoveryPlanner.State before, RecoveryPlanner.State after) { }
}
