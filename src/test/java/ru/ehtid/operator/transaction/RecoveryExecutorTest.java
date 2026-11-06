package ru.ehtid.operator.transaction;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryExecutorTest {
    private static final RecoveryPlanner.State AIR = RecoveryPlanner.State.air();
    private static final RecoveryPlanner.State STONE =
            new RecoveryPlanner.State("minecraft:stone", "Block{minecraft:stone}", null);
    private static final RecoveryPlanner.State OAK =
            new RecoveryPlanner.State("minecraft:oak_planks", "Block{minecraft:oak_planks}", null);

    @Test
    void untouchedJournalNeedsNoRollback() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        var plan = RecoveryPlanner.inspect(journal, world);
        assertEquals(RecoveryPlanner.Status.UNCHANGED, plan.status());
        assertEquals(0, plan.appliedCells());
        assertEquals(4, plan.alreadyOriginal());
        var result = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.NOTHING_TO_RESTORE, result.outcome());
        assertEquals(0, world.writes);
    }

    @Test
    void partialMoveIsRestoredWithoutTouchingOriginalCells() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.put(new RecoveryPlanner.Position(0, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(10, 64, 0), STONE);
        assertEquals(RecoveryPlanner.Status.RESTORE_NEEDED,
                RecoveryPlanner.inspect(journal, world).status());
        var result = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.COMPLETED, result.outcome());
        assertEquals(2, result.restored());
        assertEquals(0, result.remaining());
        assertEquals(STONE, world.data.get(new RecoveryPlanner.Position(0, 64, 0)));
        assertEquals(AIR, world.data.get(new RecoveryPlanner.Position(10, 64, 0)));
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
    }

    @Test
    void fullyMovedPlainBlocksCanBeRestoredAndRepeatedSafely() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.put(new RecoveryPlanner.Position(0, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(1, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(10, 64, 0), STONE);
        world.data.put(new RecoveryPlanner.Position(11, 64, 0), OAK);

        assertEquals(RecoveryPlanner.Status.RESTORE_NEEDED,
                RecoveryPlanner.inspect(journal, world).status());
        var first = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.COMPLETED, first.outcome());
        assertEquals(4, first.restored());

        var repeated = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.NOTHING_TO_RESTORE, repeated.outcome());
        assertEquals(4, world.writes, "retry must never duplicate writes");
    }

    @Test
    void forcedInterruptionCanBeResumedWithSameJournal() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.put(new RecoveryPlanner.Position(0, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(1, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(10, 64, 0), STONE);
        world.data.put(new RecoveryPlanner.Position(11, 64, 0), OAK);
        world.failAfterSuccessfulWrites = 2;

        var first = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.INTERRUPTED, first.outcome());
        assertEquals(2, first.restored());
        assertEquals(2, first.remaining());

        world.failAfterSuccessfulWrites = Integer.MAX_VALUE;
        var resumed = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.COMPLETED, resumed.outcome());
        assertEquals(2, resumed.restored());
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
        assertEquals(4, world.writes);
    }

    @Test
    void unknownThirdPartyBlockBlocksEntireRecovery() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.put(new RecoveryPlanner.Position(0, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(11, 64, 0),
                new RecoveryPlanner.State("minecraft:diamond_block",
                        "Block{minecraft:diamond_block}", null));
        var result = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.BLOCKED, result.outcome());
        assertEquals("CONFLICT", result.reason());
        assertEquals(0, world.writes);
        assertTrue(RecoveryPlanner.inspect(journal, world).changes().isEmpty());
    }

    @Test
    void unloadedCellBlocksRecovery() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.remove(new RecoveryPlanner.Position(11, 64, 0));
        var result = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.BLOCKED, result.outcome());
        assertEquals("UNLOADED", result.reason());
        assertEquals(0, world.writes);
    }

    @Test
    void concurrentChangeStopsWithoutOverwritingNewData() {
        TransactionJournal journal = journal();
        FakeWorld world = new FakeWorld(journal);
        world.data.put(new RecoveryPlanner.Position(0, 64, 0), AIR);
        world.data.put(new RecoveryPlanner.Position(10, 64, 0), STONE);
        world.interfereWithNextWrite = true;
        var result = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.CONCURRENT_CHANGE, result.outcome());
        assertEquals(0, result.restored());
        assertEquals(0, world.writes);
    }

    @Test
    void mismatchedTranslationBlocksEvenChecksumValidJournal() {
        TransactionJournal journal = journal();
        journal.offsetX = 9;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertEquals(RecoveryPlanner.Status.INVALID_JOURNAL,
                RecoveryPlanner.inspect(journal, new FakeWorld(journal)).status());
    }

    @Test
    void blockEntityPreimagesNeedDedicatedNbtSafeRestoreAdapter() {
        TransactionJournal journal = journal();
        journal.source.before.set(0, new TransactionJournal.Cell(
                0, 64, 0, "minecraft:chest", "Block{minecraft:chest}",
                "{x:0,y:64,z:0,Items:[]}"));
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        var result = RecoveryExecutor.restore(journal, new FakeWorld(journal));
        assertEquals(RecoveryExecutor.Outcome.BLOCKED, result.outcome());
        assertEquals("UNSUPPORTED_BLOCK_ENTITY", result.reason());
    }

    private static TransactionJournal journal() {
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = "e01758ab-8843-4ddd-993e-d4ce8a70393b";
        journal.ownerUuid = "10bf24fa-8e43-496a-a290-9c9c6fafc15e";
        journal.sourceSnapshotId = "91191f62-c782-4828-9809-53e0db87e253";
        journal.sourceSnapshotSha256 = "abc123";
        journal.dimension = "minecraft:overworld";
        journal.capturedAtTick = 12L;
        journal.offsetX = 10;
        journal.source.minX = 0;
        journal.source.maxX = 1;
        journal.source.minY = journal.source.maxY = 64;
        journal.source.minZ = journal.source.maxZ = 0;
        journal.target.minX = 10;
        journal.target.maxX = 11;
        journal.target.minY = journal.target.maxY = 64;
        journal.target.minZ = journal.target.maxZ = 0;
        journal.source.before.add(new TransactionJournal.Cell(0,64,0,
                STONE.blockId(), STONE.blockState(), STONE.blockEntitySnbt()));
        journal.source.before.add(new TransactionJournal.Cell(1,64,0,
                OAK.blockId(), OAK.blockState(), OAK.blockEntitySnbt()));
        journal.target.before.add(new TransactionJournal.Cell(10,64,0,
                AIR.blockId(), AIR.blockState(), AIR.blockEntitySnbt()));
        journal.target.before.add(new TransactionJournal.Cell(11,64,0,
                AIR.blockId(), AIR.blockState(), AIR.blockEntitySnbt()));
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }

    private static final class FakeWorld implements RecoveryExecutor.MutableWorld {
        private final Map<RecoveryPlanner.Position, RecoveryPlanner.State> data = new HashMap<>();
        int writes;
        int failAfterSuccessfulWrites = Integer.MAX_VALUE;
        boolean interfereWithNextWrite;

        FakeWorld(TransactionJournal journal) {
            for (var cell : TransactionJournal.reversePreimages(journal)) {
                data.put(new RecoveryPlanner.Position(cell.x(), cell.y(), cell.z()),
                        RecoveryPlanner.State.of(cell));
            }
        }

        @Override
        public RecoveryPlanner.State read(int x, int y, int z) {
            return data.get(new RecoveryPlanner.Position(x, y, z));
        }

        @Override
        public boolean compareAndRestore(RecoveryPlanner.Position position,
                                         RecoveryPlanner.State expected,
                                         RecoveryPlanner.State restore) {
            if (writes >= failAfterSuccessfulWrites)
                throw new IllegalStateException("injected crash");
            if (interfereWithNextWrite) {
                interfereWithNextWrite = false;
                return false;
            }
            if (!expected.equals(data.get(position))) return false;
            data.put(position, restore);
            writes++;
            return true;
        }
    }
}
