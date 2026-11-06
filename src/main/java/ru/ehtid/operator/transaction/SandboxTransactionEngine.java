package ru.ehtid.operator.transaction;

import java.io.IOException;
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
    }

    public enum Outcome {
        REFUSED, INTERRUPTED, COMMITTED
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
            ledger.setPhase("COMMITTED", gameTick.getAsLong());
            store.update(journal, ledger);
        } catch (IOException | RuntimeException failure) {
            return new Result(Outcome.INTERRUPTED, "WAL_COMMIT_FAILED", count, acknowledged);
        }
        return new Result(Outcome.COMMITTED, "VERIFIED", count, acknowledged);
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
