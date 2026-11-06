package ru.ehtid.operator.transaction;

/**
 * Generic compare-and-restore engine for fault-injection tests and future
 * server-thread adapters. No Minecraft world adapter is exposed in this
 * version; there is deliberately no in-game execution command.
 */
public final class RecoveryExecutor {
    private RecoveryExecutor() { }

    public interface MutableWorld extends RecoveryPlanner.WorldView {
        /**
         * Atomically change one cell only if it still exactly matches expected.
         * Implementations must preserve block entity data, and must not expose
         * an unconditional "setBlock" operation through this interface.
         */
        boolean compareAndRestore(RecoveryPlanner.Position position,
                                  RecoveryPlanner.State expected,
                                  RecoveryPlanner.State restore);
    }

    /**
     * Optional durable write-ahead checkpoint callbacks.
     * A future Minecraft adapter must fsync an intent BEFORE applying a cell
     * and record completion only AFTER a successful compare-and-write.
     * They are exercised through simulated-world fault injection in 0.14.
     */
    public interface CheckpointSink {
        void beforeWrite(int sequence, RecoveryPlanner.Change change);
        void afterWrite(int sequence, RecoveryPlanner.Change change);

        CheckpointSink NOOP = new CheckpointSink() {
            @Override public void beforeWrite(int sequence, RecoveryPlanner.Change change) { }
            @Override public void afterWrite(int sequence, RecoveryPlanner.Change change) { }
        };
    }

    public enum Outcome {
        NOTHING_TO_RESTORE, COMPLETED, BLOCKED, INTERRUPTED, CONCURRENT_CHANGE
    }

    public record Result(Outcome outcome, int restored, int remaining, String reason) { }

    /**
     * On exception after any successful write, the unchanged journal can be
     * inspected again. Already restored cells are naturally idempotent.
     */
    public static Result restore(TransactionJournal journal, MutableWorld world) {
        return restore(journal, world, CheckpointSink.NOOP);
    }

    /** Compare/write never begins if the full journal preflight is unsafe. */
    public static Result restore(TransactionJournal journal, MutableWorld world,
                                 CheckpointSink checkpointSink) {
        if (checkpointSink == null) {
            return new Result(Outcome.BLOCKED, 0, 0, "NO_CHECKPOINT_SINK");
        }
        RecoveryPlanner.Plan plan = RecoveryPlanner.inspect(journal, world);
        if (!plan.safeToRestore()) {
            return new Result(Outcome.BLOCKED, 0, plan.appliedCells(),
                    plan.status().name());
        }
        if (plan.status() == RecoveryPlanner.Status.UNCHANGED) {
            return new Result(Outcome.NOTHING_TO_RESTORE, 0, 0, "ALREADY_ORIGINAL");
        }

        int restored = 0;
        for (RecoveryPlanner.Change change : plan.changes()) {
            try {
                // A caller-supplied sink can persist an intent *before* a write.
                checkpointSink.beforeWrite(restored, change);
                // A guarded write prevents overwriting a newly changed cell.
                if (!world.compareAndRestore(change.position(),
                        change.applied(), change.before())) {
                    return new Result(Outcome.CONCURRENT_CHANGE, restored,
                            plan.changes().size() - restored,
                            "WORLD_CHANGED_DURING_RESTORE");
                }
                restored++;
                checkpointSink.afterWrite(restored, change);
            } catch (RuntimeException interrupted) {
                return new Result(Outcome.INTERRUPTED, restored,
                        plan.changes().size() - restored,
                        "RESTORATION_OR_CHECKPOINT_INTERRUPTED");
            }
        }
        // A successful sequence of CAS writes is not proof of a complete
        // restore if other systems can modify world cells concurrently.
        RecoveryPlanner.Plan verification = RecoveryPlanner.inspect(journal, world);
        if (verification.status() != RecoveryPlanner.Status.UNCHANGED) {
            return new Result(Outcome.CONCURRENT_CHANGE, restored,
                    verification.appliedCells(),
                    "POST_RESTORE_VERIFICATION_" + verification.status());
        }
        return new Result(Outcome.COMPLETED, restored, 0, "PREIMAGES_RESTORED");
    }
}
