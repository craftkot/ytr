package ru.ehtid.operator.transaction;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryCheckpointLedgerTest {

    @Test
    void retainsVerifiedPreparedAndAuditedHistoryAcrossJsonRestart() {
        TransactionJournal journal = journal();
        RecoveryCheckpointLedger ledger = RecoveryCheckpointLedger.initialize(journal, 500L);
        assertTrue(ledger.valid());
        assertEquals("PREPARED", ledger.latest().phase());

        RecoveryPlanner.Plan scan = new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.RESTORE_NEEDED, 4, 2, 2, 0, 0, List.of());
        ledger.appendAudit(journal, 501L, scan);
        assertTrue(ledger.valid());
        assertEquals(1, ledger.latest().sequence());
        assertEquals("RESTORE_NEEDED", ledger.latest().phase());

        // Simulates an actual process restart: an independent Gson decode.
        String serialized = new Gson().toJson(ledger);
        RecoveryCheckpointLedger afterRestart =
                new Gson().fromJson(serialized, RecoveryCheckpointLedger.class);

        assertTrue(afterRestart.valid());
        assertTrue(afterRestart.matches(journal));
        assertEquals(2, afterRestart.checkpoints.size());
        assertEquals(ledger.latest().hash(), afterRestart.latest().hash());

        afterRestart.appendAudit(journal, 502L, new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.UNCHANGED, 4, 4, 0, 0, 0, List.of()));
        assertEquals(3, afterRestart.checkpoints.size());
        assertEquals("UNCHANGED", afterRestart.latest().phase());
        assertTrue(afterRestart.valid());
    }

    @Test
    void detectsHistoricalCheckpointTamperingEvenIfLatestHashIsUntouched() {
        TransactionJournal journal = journal();
        RecoveryCheckpointLedger ledger = RecoveryCheckpointLedger.initialize(journal, 100L);
        ledger.appendAudit(journal, 101L, new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.UNCHANGED, 4, 4, 0, 0, 0, List.of()));
        var original = ledger.checkpoints.get(0);
        ledger.checkpoints.set(0, new RecoveryCheckpointLedger.Checkpoint(
                original.sequence(), original.gameTick(), original.phase(),
                3, original.alreadyOriginal(), original.predictedWrites(),
                original.conflicts(), original.unloaded(),
                original.previousHash(), original.hash()));
        assertFalse(ledger.valid());
        assertThrows(IllegalArgumentException.class, () -> ledger.appendAudit(
                journal, 102L, new RecoveryPlanner.Plan(
                        RecoveryPlanner.Status.UNCHANGED, 4, 4, 0, 0, 0, List.of())));
    }

    @Test
    void journalIdentityAndChecksumAreBoundToEveryCheckpoint() {
        TransactionJournal journal = journal();
        RecoveryCheckpointLedger ledger = RecoveryCheckpointLedger.initialize(journal, 100L);
        journal.ownerUuid = "25ae3000-bbd6-42e7-8ab7-ff1d41d9e381";
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        assertFalse(ledger.matches(journal));
        assertThrows(IllegalArgumentException.class, () -> ledger.appendAudit(
                journal, 101L, new RecoveryPlanner.Plan(
                        RecoveryPlanner.Status.UNCHANGED, 4, 4, 0, 0, 0, List.of())));
    }

    @Test
    void deniesUnboundedAuditHistoryAndOutOfOrderGameTicks() {
        TransactionJournal journal = journal();
        RecoveryCheckpointLedger ledger = RecoveryCheckpointLedger.initialize(journal, 100L);
        RecoveryPlanner.Plan scan = new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.UNCHANGED, 4, 4, 0, 0, 0, List.of());

        assertThrows(IllegalArgumentException.class,
                () -> ledger.appendAudit(journal, 99L, scan));
        for (int i = 1; i < RecoveryCheckpointLedger.MAX_EVENTS; i++) {
            ledger.appendAudit(journal, 100L + i, scan);
        }
        assertEquals(RecoveryCheckpointLedger.MAX_EVENTS, ledger.checkpoints.size());
        assertTrue(ledger.valid());
        assertThrows(IllegalStateException.class,
                () -> ledger.appendAudit(journal, 500L, scan));
    }

    @Test
    void impossibleOrMalformedAuditCountsInvalidateTheChain() {
        TransactionJournal journal = journal();
        RecoveryCheckpointLedger ledger = RecoveryCheckpointLedger.initialize(journal, 100L);
        ledger.appendAudit(journal, 101L, new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.CONFLICT, 4, 2, 0, 2, 0, List.of()));
        assertTrue(ledger.valid());

        var previous = ledger.latest();
        ledger.checkpoints.set(1, new RecoveryCheckpointLedger.Checkpoint(
                previous.sequence(), previous.gameTick(), previous.phase(),
                4, 5, previous.predictedWrites(), previous.conflicts(),
                previous.unloaded(), previous.previousHash(), previous.hash()));
        assertFalse(ledger.valid());
    }

    @Test
    void invalidPreimageJournalCannotOpenRecoveryLedger() {
        TransactionJournal journal = journal();
        journal.source.before.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> RecoveryCheckpointLedger.initialize(journal, 100L));
    }

    private static TransactionJournal journal() {
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = "d2d4307f-6a40-4870-ae93-3146bcf02cd4";
        journal.ownerUuid = "faeac711-1aa5-4b4e-bf9c-79a0dd454b56";
        journal.sourceSnapshotId = "ee38df18-600f-451d-b075-740b043318f7";
        journal.sourceSnapshotSha256 = "snapshot-sha-placeholder";
        journal.dimension = "minecraft:overworld";
        journal.capturedAtTick = 100L;
        journal.offsetX = 10;
        journal.source.minX = 0; journal.source.maxX = 1;
        journal.source.minY = journal.source.maxY = 64;
        journal.source.minZ = journal.source.maxZ = 0;
        journal.target.minX = 10; journal.target.maxX = 11;
        journal.target.minY = journal.target.maxY = 64;
        journal.target.minZ = journal.target.maxZ = 0;
        for (int x = 0; x < 2; x++) {
            journal.source.before.add(new TransactionJournal.Cell(x, 64, 0,
                    "minecraft:oak_planks", "Block{minecraft:oak_planks}", null));
            journal.target.before.add(new TransactionJournal.Cell(x + 10, 64, 0,
                    "minecraft:air", "Block{minecraft:air}", null));
        }
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }
}
