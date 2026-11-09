package ru.ehtid.operator.transaction;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TunnelFoldEngineTest {
    private static final RecoveryPlanner.State AIR = RecoveryPlanner.State.air();
    private static final RecoveryPlanner.State STONE =
            new RecoveryPlanner.State("minecraft:stone", "Block{minecraft:stone}", null);

    @Test void aOneStoneFoldCommitsAndRestoresFromDurableWal() {
        var journal = fixture();
        var world = new MemoryWorld(journal);
        var store = new Store();
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED,
                SandboxTransactionEngine.apply(journal, world, () -> 100L, store).outcome(),
                "House relocation must still require at least 24 blocks");
        assertNull(store.state);
        var apply = SandboxTransactionEngine.applyTunnelFold(
                journal, world, () -> 100L, store);
        assertEquals(SandboxTransactionEngine.Outcome.COMMITTED, apply.outcome());
        assertEquals(2, apply.confirmedWrites());
        assertEquals(AIR, world.read(0, 45, 0));
        assertEquals(STONE, world.read(1, 45, 0));
        assertEquals("COMMITTED", store.reload().phase);
        var restore = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, () -> 200L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, restore.outcome());
        assertEquals(STONE, world.read(0, 45, 0));
        assertEquals(AIR, world.read(1, 45, 0));
        assertEquals(2, store.flushes);
    }

    @Test void wrongMarkerOrVerticalDisplacementFailsClosed() {
        var journal = fixture();
        journal.sourceSnapshotId = "random";
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        var store = new Store();
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED,
                SandboxTransactionEngine.applyTunnelFold(
                        journal, new MemoryWorld(journal), () -> 1L, store).outcome());
        assertNull(store.state);
        journal = fixture();
        journal.offsetY = 1;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(SandboxTransactionEngine.isTunnelFold(journal));
    }

    @Test void interruptedApplyIntentCanBeRestored() {
        var journal = fixture();
        var world = new MemoryWorld(journal);
        var store = new Store();
        store.failUpdate = 2;
        var apply = SandboxTransactionEngine.applyTunnelFold(
                journal, world, () -> 1L, store);
        assertEquals(SandboxTransactionEngine.Outcome.INTERRUPTED, apply.outcome());
        assertEquals("APPLYING", store.reload().phase);
        assertEquals("APPLY", store.reload().pendingOperation);
        store.failUpdate = -1;
        var rollback = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, () -> 2L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, rollback.outcome());
        assertEquals(STONE, world.read(0, 45, 0));
        assertEquals(AIR, world.read(1, 45, 0));
    }


    @Test void v2TwoStoneStripCommitsFourWritesAndRestoresBothCells() {
        var journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0, 45, 0),
                new BlockPos(1, 45, 0), 3L);
        assertTrue(SandboxTransactionEngine.isTunnelFold(journal));
        assertEquals(TunnelFoldService.MARKER_V2, journal.sourceSnapshotId);
        var world = new MemoryWorld(journal);
        var store = new Store();
        var applied = SandboxTransactionEngine.applyTunnelFold(
                journal, world, () -> 101L, store);
        assertEquals(SandboxTransactionEngine.Outcome.COMMITTED, applied.outcome());
        assertEquals(2, applied.transferredBlocks());
        assertEquals(4, applied.confirmedWrites());
        assertEquals(4, store.reload().appliedWrites);
        for (int z = 0; z <= 1; z++) {
            assertEquals(AIR, world.read(0, 45, z));
            assertEquals(STONE, world.read(1, 45, z));
        }
        // Test a copied WAL record (simulates freshly read execution metadata).
        var restored = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, () -> 102L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, restored.outcome());
        assertEquals(4, restored.confirmedWrites());
        assertEquals(4, store.reload().restoredWrites);
        for (int z = 0; z <= 1; z++) {
            assertEquals(STONE, world.read(0, 45, z));
            assertEquals(AIR, world.read(1, 45, z));
        }
        assertEquals(2, store.flushes);
    }

    @Test void v2RejectsV1MislabelledOrNonStonePairsBeforeAnyWal() {
        var journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0, 45, 0),
                new BlockPos(1, 45, 0), 4L);
        journal.sourceSnapshotId = TunnelFoldService.MARKER;
        journal.sourceSnapshotSha256 = TunnelFoldService.MARKER;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(SandboxTransactionEngine.isTunnelFold(journal),
                "V1 one-block entries must never authorize two-cell writes");
        var store = new Store();
        var attempted = SandboxTransactionEngine.applyTunnelFold(
                journal, new MemoryWorld(journal), () -> 1L, store);
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED, attempted.outcome());
        assertNull(store.state);

        journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0, 45, 0),
                new BlockPos(1, 45, 0), 5L);
        journal.source.before.set(1, new TransactionJournal.Cell(
                0,45,1,"minecraft:cobblestone","Block{minecraft:cobblestone}",null));
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(SandboxTransactionEngine.isTunnelFold(journal));
        var other = new Store();
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED,
                SandboxTransactionEngine.applyTunnelFold(
                        journal, new MemoryWorld(journal), () -> 1L, other).outcome());
        assertNull(other.state);
    }

    @Test void v2InterruptedWalCanSafelyRollbackPartiallyWrittenStrip() {
        var journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0, 45, 0),
                new BlockPos(1, 45, 0), 6L);
        var world = new MemoryWorld(journal);
        var store = new Store();
        store.failUpdate = 2; // Crash after writing first target, before ACK.
        var interrupted = SandboxTransactionEngine.applyTunnelFold(
                journal, world, () -> 101L, store);
        assertEquals(SandboxTransactionEngine.Outcome.INTERRUPTED, interrupted.outcome());
        assertEquals("APPLY", store.reload().pendingOperation);
        store.failUpdate = -1;
        var rolledBack = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, () -> 102L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, rolledBack.outcome());
        for (int z = 0; z <= 1; z++) {
            assertEquals(STONE, world.read(0, 45, z));
            assertEquals(AIR, world.read(1, 45, z));
        }
    }

    @Test void worldFieldV1UsesRealV2FourWriteWalAndIsReversible() {
        var journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0,45,0),
                new BlockPos(1,45,0), 24000L);
        journal.sourceSnapshotId = WorldFieldService.MARKER;
        journal.sourceSnapshotSha256 = WorldFieldService.MARKER;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertTrue(SandboxTransactionEngine.isTunnelFold(journal));
        var world = new MemoryWorld(journal);
        var store = new Store();
        var apply = SandboxTransactionEngine.applyTunnelFold(
                journal, world, ()->24001L, store);
        assertEquals(SandboxTransactionEngine.Outcome.COMMITTED, apply.outcome());
        assertEquals(4, store.reload().totalWrites);
        assertEquals(4, store.reload().appliedWrites);
        var rollback = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, ()->24002L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, rollback.outcome());
        assertEquals(STONE, world.read(0,45,0));
        assertEquals(STONE, world.read(0,45,1));
        assertEquals(AIR, world.read(1,45,0));
        assertEquals(AIR, world.read(1,45,1));
    }

    @Test void worldFieldRejectsForgedMarkerWithoutDurableLedger() {
        var journal = fixture();
        journal.sourceSnapshotId = WorldFieldService.MARKER;
        journal.sourceSnapshotSha256 = WorldFieldService.MARKER;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(SandboxTransactionEngine.isTunnelFold(journal),
                "A global field always requires a full two-block strip");
        var store = new Store();
        assertEquals(SandboxTransactionEngine.Outcome.REFUSED,
                SandboxTransactionEngine.applyTunnelFold(
                        journal, new MemoryWorld(journal), ()->1L, store).outcome());
        assertNull(store.state);
    }

    @Test void deferredFieldV2ReservesBoth64BlockSectorsAndRollsBackAfterCrash() {
        var journal = TunnelFoldService.twoCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(256,202,0),
                new BlockPos(255,202,0), 24_000L);
        journal.sourceSnapshotId = WorldFieldService.MARKER_V2;
        journal.sourceSnapshotSha256 = WorldFieldService.MARKER_V2;
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertTrue(SandboxTransactionEngine.isTunnelFold(journal));
        assertTrue(ChunkBoundaryPolicy.spansExactlyTwoChunks(journal));
        var sectors = WorldFieldSectorFootprint.sectors(journal);
        assertEquals(java.util.Set.of("minecraft:overworld|3,0",
                "minecraft:overworld|4,0"), sectors);
        var world = new MemoryWorld(journal);
        var store = new Store();
        store.failUpdate = 2;
        var interrupted = SandboxTransactionEngine.applyTunnelFold(
                journal, world, () -> 24001L, store);
        assertEquals(SandboxTransactionEngine.Outcome.INTERRUPTED, interrupted.outcome());
        assertEquals("APPLY", store.reload().pendingOperation);
        store.failUpdate = -1;
        var restored = SandboxTransactionEngine.rollback(
                journal, store.reload(), world, () -> 24002L, store);
        assertEquals(SandboxTransactionEngine.Outcome.ROLLED_BACK, restored.outcome());
        for (int z = 0; z < 2; z++) {
            assertEquals(STONE, world.read(256,202,z));
            assertEquals(AIR, world.read(255,202,z));
        }
        // The injected crash happens after one write, not after all four:
        // restoredWrites counts the cells that actually needed recovery.
        assertEquals(restored.confirmedWrites(), store.reload().restoredWrites);
        assertTrue(store.reload().restoredWrites > 0);
        assertTrue(store.reload().restoredWrites <= 4);
        assertEquals("ROLLED_BACK", store.reload().phase);
    }

    private static TransactionJournal fixture() {
        return TunnelFoldService.oneCellJournal(
                UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new BlockPos(0, 45, 0),
                new BlockPos(1, 45, 0), 1L);
    }

    private static final class MemoryWorld implements RecoveryExecutor.MutableWorld {
        private final Map<RecoveryPlanner.Position, RecoveryPlanner.State> values = new HashMap<>();
        MemoryWorld(TransactionJournal j) {
            for (var cell : TransactionJournal.reversePreimages(j)) {
                values.put(new RecoveryPlanner.Position(cell.x(), cell.y(), cell.z()),
                        RecoveryPlanner.State.of(cell));
            }
        }
        @Override public RecoveryPlanner.State read(int x, int y, int z) {
            return values.get(new RecoveryPlanner.Position(x, y, z));
        }
        @Override public boolean compareAndRestore(RecoveryPlanner.Position position,
                               RecoveryPlanner.State expected, RecoveryPlanner.State restore) {
            if (!expected.equals(values.get(position))) return false;
            values.put(position, restore);
            return true;
        }
    }

    private static final class Store implements SandboxTransactionEngine.LedgerStore {
        String state;
        int updateCount;
        int failUpdate = -1;
        int flushes;
        RelocationExecutionLedger reload() {
            return new Gson().fromJson(state, RelocationExecutionLedger.class);
        }
        @Override public void create(TransactionJournal j, RelocationExecutionLedger ledger)
                throws IOException {
            if (state != null) throw new IOException("Duplicate ledger");
            state = new Gson().toJson(ledger);
        }
        @Override public void update(TransactionJournal j, RelocationExecutionLedger ledger)
                throws IOException {
            if (++updateCount == failUpdate) throw new IOException("Injected WAL failure");
            state = new Gson().toJson(ledger);
        }
        @Override public void flushWorld() {
            flushes++;
        }
    }
}
