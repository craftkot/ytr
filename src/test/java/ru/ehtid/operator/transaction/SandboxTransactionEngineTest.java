package ru.ehtid.operator.transaction;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SandboxTransactionEngineTest {
    private static final RecoveryPlanner.State AIR = RecoveryPlanner.State.air();
    private static final RecoveryPlanner.State STONE =
            new RecoveryPlanner.State("minecraft:stone", "Block{minecraft:stone}", null);

    @Test
    void actualApplyPathMovesHollowHouseAndAbstractRollbackRestoresIt() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        var applied = SandboxTransactionEngine.apply(journal, world, () -> 101L, store);

        assertEquals(SandboxTransactionEngine.Outcome.COMMITTED, applied.outcome());
        assertEquals(26, applied.transferredBlocks());
        assertEquals(52, applied.confirmedWrites());
        assertEquals(52, world.writes);
        assertEquals("COMMITTED", store.reload().phase);
        assertEquals(52, store.reload().appliedWrites);
        assertEquals(RecoveryPlanner.Status.RESTORE_NEEDED,
                RecoveryPlanner.inspect(journal, world).status());
        assertEquals(AIR, world.read(0, 64, 0));
        assertEquals(STONE, world.read(10, 64, 0));

        // The same RecoveryExecutor used by the in-game rollback path.
        var reverted = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.COMPLETED, reverted.outcome());
        assertEquals(52, reverted.restored());
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
    }

    @Test
    void walCreationFailureGuaranteesZeroWrites() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        store.failCreate = true;
        var result = SandboxTransactionEngine.apply(journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED, result.outcome());
        assertEquals("WAL_CREATE_FAILED", result.reason());
        assertEquals(0, world.writes);
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
    }

    @Test
    void crashAfterFirstBlockButBeforeAcknowledgementLeavesRecoverableIntent() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        // First update = durable intent. Second update = ACK, deliberately fails.
        store.failUpdateAt = 2;
        var result = SandboxTransactionEngine.apply(journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.INTERRUPTED, result.outcome());
        assertEquals("WRITE_OR_WAL_INTERRUPTED", result.reason());
        assertEquals(1, world.writes);
        assertEquals(0, result.confirmedWrites());
        assertEquals("APPLY", store.reload().pendingOperation);
        assertEquals("APPLYING", store.reload().phase);

        var inspected = RecoveryPlanner.inspect(journal, world);
        assertEquals(RecoveryPlanner.Status.RESTORE_NEEDED, inspected.status());
        assertEquals(1, inspected.appliedCells());
        var recovery = RecoveryExecutor.restore(journal, world);
        assertEquals(RecoveryExecutor.Outcome.COMPLETED, recovery.outcome());
        assertEquals(1, recovery.restored());
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
    }

    @Test
    void staleOrOccupiedWorldRefusesBeforeCreatingAnyWal() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        world.cells.put(new RecoveryPlanner.Position(10, 64, 0),
                new RecoveryPlanner.State("minecraft:diamond_block",
                        "Block{minecraft:diamond_block}", null));
        var result = SandboxTransactionEngine.apply(journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED, result.outcome());
        assertEquals("PRECHECK_CONFLICT", result.reason());
        assertEquals(0, world.writes);
        assertNull(store.state);
    }

    @Test
    void writeRefusalKeepsDurableIntentAndNeverRunsRemainingSteps() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        world.refuseFirstWrite = true;
        var result = SandboxTransactionEngine.apply(journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.INTERRUPTED, result.outcome());
        assertEquals("WORLD_CHANGED_OR_WRITE_FAILED", result.reason());
        assertEquals(0, world.writes);
        assertEquals("APPLY", store.reload().pendingOperation);
        assertEquals(RecoveryPlanner.Status.UNCHANGED,
                RecoveryPlanner.inspect(journal, world).status());
    }

    @Test
    void targetNotRecordedAirOrInvalidOffsetRefusesWithoutWal() {
        TransactionJournal journal = shellJournal();
        World world = new World(journal);
        Store store = new Store();
        journal.target.before.set(0, new TransactionJournal.Cell(10,64,0,
                "minecraft:stone", "Block{minecraft:stone}", null));
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        var result = SandboxTransactionEngine.apply(journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED, result.outcome());
        assertNull(store.state);

        journal = shellJournal();
        journal.offsetX = 9;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED,
                SandboxTransactionEngine.apply(journal, new World(journal),
                        () -> 100L, new Store()).outcome());
    }

    private static TransactionJournal shellJournal() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "2ec54f1d-6bf3-4f7c-8989-8f6e2b1ef561";
        j.ownerUuid = "c32ab184-2bb6-4ccf-9133-d20e58f9a7d4";
        j.sourceSnapshotId = "e84010a9-3c43-46c0-b1dd-dad9257df36b";
        j.sourceSnapshotSha256 = "snapshot";
        j.dimension = "minecraft:overworld";
        j.offsetX = 10;
        j.source.minX = 0; j.source.maxX = 2;
        j.source.minY = 64; j.source.maxY = 66;
        j.source.minZ = 0; j.source.maxZ = 2;
        j.target.minX = 10; j.target.maxX = 12;
        j.target.minY = 64; j.target.maxY = 66;
        j.target.minZ = 0; j.target.maxZ = 2;
        for (int x = 0; x <= 2; x++) {
            for (int y = 64; y <= 66; y++) {
                for (int z = 0; z <= 2; z++) {
                    boolean hollow = x == 1 && y == 65 && z == 1;
                    String id = hollow ? "minecraft:air" : "minecraft:stone";
                    String state = "Block{" + id + "}";
                    j.source.before.add(new TransactionJournal.Cell(x, y, z, id, state, null));
                    j.target.before.add(new TransactionJournal.Cell(
                            x + 10, y, z, "minecraft:air", "Block{minecraft:air}", null));
                }
            }
        }
        j.sha256 = TransactionIntegrity.fingerprint(j);
        return j;
    }

    private static final class Store implements SandboxTransactionEngine.LedgerStore {
        String state;
        int updates;
        int failUpdateAt = -1;
        boolean failCreate;

        RelocationExecutionLedger reload() {
            return new Gson().fromJson(state, RelocationExecutionLedger.class);
        }

        @Override
        public void create(TransactionJournal journal, RelocationExecutionLedger ledger)
                throws IOException {
            if (failCreate) throw new IOException("injected WAL creation error");
            assertNull(state, "create must not overwrite another transaction");
            state = new Gson().toJson(ledger);
        }

        @Override
        public void update(TransactionJournal journal, RelocationExecutionLedger ledger)
                throws IOException {
            updates++;
            if (updates == failUpdateAt) throw new IOException("injected WAL failure");
            state = new Gson().toJson(ledger);
        }
    }

    private static final class World implements RecoveryExecutor.MutableWorld {
        final Map<RecoveryPlanner.Position, RecoveryPlanner.State> cells = new HashMap<>();
        int writes;
        boolean refuseFirstWrite;

        World(TransactionJournal journal) {
            for (TransactionJournal.Cell c : TransactionJournal.reversePreimages(journal)) {
                cells.put(new RecoveryPlanner.Position(c.x(), c.y(), c.z()),
                        RecoveryPlanner.State.of(c));
            }
        }

        @Override
        public RecoveryPlanner.State read(int x, int y, int z) {
            return cells.get(new RecoveryPlanner.Position(x, y, z));
        }

        @Override
        public boolean compareAndRestore(RecoveryPlanner.Position pos,
                                         RecoveryPlanner.State expected,
                                         RecoveryPlanner.State restored) {
            if (refuseFirstWrite) { refuseFirstWrite = false; return false; }
            if (!expected.equals(cells.get(pos))) return false;
            cells.put(pos, restored);
            writes++;
            return true;
        }
    }
}
