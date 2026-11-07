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
