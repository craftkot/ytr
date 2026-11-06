package ru.ehtid.operator.transaction;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelocationExecutionLedgerTest {

    @Test
    void survivesRestartAfterUnacknowledgedWorldWrite() {
        TransactionJournal journal = journal();
        RelocationExecutionLedger state = RelocationExecutionLedger.start(journal, 4, 500);
        assertTrue(state.valid());
        state.beginWrite("APPLY", 10, 64, 0, 501);
        assertTrue(state.valid());
        assertEquals("APPLYING", state.phase);

        // A process restart happens after an intent, potentially after the world
        // changed, but before we persisted the completion acknowledgement.
        RelocationExecutionLedger reloaded = new Gson().fromJson(
                new Gson().toJson(state), RelocationExecutionLedger.class);
        assertTrue(reloaded.matches(journal));
        assertEquals("APPLY", reloaded.pendingOperation);
        assertThrows(IllegalStateException.class, () -> reloaded.setPhase("COMMITTED", 502));
        reloaded.beginRollbackAfterInterruptedWrite(502);
        assertEquals("ROLLING_BACK", reloaded.phase);
        assertEquals("", reloaded.pendingOperation);
        assertTrue(reloaded.matches(journal));
    }

    @Test
    void validatesApplyAndRollbackPhaseTransitions() {
        TransactionJournal journal = journal();
        RelocationExecutionLedger state = RelocationExecutionLedger.start(journal, 2, 100);
        state.beginWrite("APPLY", 10, 64, 0, 101);
        state.acknowledgeWrite("APPLY", 101);
        state.beginWrite("APPLY", 0, 64, 0, 102);
        state.acknowledgeWrite("APPLY", 102);
        state.setPhase("COMMITTED", 103);
        assertEquals(2, state.appliedWrites);
        assertThrows(IllegalStateException.class, () -> state.beginWrite("APPLY", 10,64,0,104));
        state.setPhase("ROLLING_BACK", 104);
        state.beginWrite("RESTORE", 0, 64, 0, 105);
        state.acknowledgeWrite("RESTORE", 105);
        state.beginWrite("RESTORE", 10, 64, 0, 106);
        state.acknowledgeWrite("RESTORE", 106);
        state.setPhase("ROLLED_BACK", 107);
        assertEquals(2, state.restoredWrites);
        assertTrue(state.valid());
        assertThrows(IllegalStateException.class, () -> state.setPhase("COMMITTED",108));
    }

    @Test
    void rejectsCorruptionOwnershipChangesAndNullJournal() {
        TransactionJournal journal = journal();
        RelocationExecutionLedger state = RelocationExecutionLedger.start(journal, 2, 100);
        state.pendingX = 9999;
        assertFalse(state.valid());
        state.sign();
        assertTrue(state.valid());
        journal.ownerUuid = "d524183d-ad1d-4907-8da6-41a9ee91021b";
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(state.matches(journal));
        assertFalse(state.matches(null));
    }

    @Test
    void rejectUnboundedOrNegativeWriteBudgets() {
        TransactionJournal journal = journal();
        assertThrows(IllegalArgumentException.class,
                () -> RelocationExecutionLedger.start(journal, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> RelocationExecutionLedger.start(journal, 257, 100));
    }

    private static TransactionJournal journal() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "a8225dbe-f81c-402d-b699-15da73528b4a";
        j.ownerUuid = "0b0af07b-ff5b-42d6-b343-087b9089a38c";
        j.sourceSnapshotId = "978961aa-cc8f-477f-be3b-1a643d53cfe1";
        j.sourceSnapshotSha256 = "example";
        j.dimension = "minecraft:overworld";
        j.offsetX = 10;
        j.source.minX = j.source.maxX = 0;
        j.source.minY = j.source.maxY = 64;
        j.source.minZ = j.source.maxZ = 0;
        j.target.minX = j.target.maxX = 10;
        j.target.minY = j.target.maxY = 64;
        j.target.minZ = j.target.maxZ = 0;
        j.source.before.add(new TransactionJournal.Cell(
                0,64,0,"minecraft:stone","Block{minecraft:stone}",null));
        j.target.before.add(new TransactionJournal.Cell(
                10,64,0,"minecraft:air","Block{minecraft:air}",null));
        j.sha256 = TransactionIntegrity.fingerprint(j);
        return j;
    }
}
