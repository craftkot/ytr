package ru.ehtid.operator.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.ehtid.operator.transaction.RelocationExecutionLedger;
import ru.ehtid.operator.transaction.TransactionIntegrity;
import ru.ehtid.operator.transaction.TransactionJournal;

import static org.junit.jupiter.api.Assertions.*;

class RelocationWatchdogStorageTest {
    @TempDir Path temp;

    private static final String OWNER_A = "2f739601-3bf3-4e0c-8964-df84d977bf78";
    private static final String OWNER_B = "0cb12cf3-6742-4d8f-9a78-76e9d7072e2e";
    private static final String JOURNAL_A = "1ea80f91-7958-4cde-8e7d-d64894aeaa15";
    private static final String JOURNAL_B = "971181e3-0c79-41ac-ae62-9b8ef82bf9d7";

    @Test
    void freshWorldHasNoBlockingWal() throws IOException {
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(0, report.total());
        assertFalse(report.blocksNewWrites());
        assertTrue(report.summary().contains("0 checked"));
    }

    @Test
    void unfinishedOfflineOwnerBlocksAnotherOwnersNewTransfers() throws IOException {
        var a = fixture(OWNER_A, JOURNAL_A);
        var b = fixture(OWNER_B, JOURNAL_B);
        write(a, "APPLYING");
        write(b, "ROLLED_BACK");
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(2, report.total());
        assertEquals(1, report.unfinished());
        assertEquals(1, report.rolledBack());
        assertEquals(0, report.invalid());
        assertTrue(report.blocksNewWrites());
        assertTrue(report.summary().contains("APPLYING"));
        assertTrue(report.summary().contains(OWNER_A));
        assertFalse(report.summary().contains("Items:"));
    }

    @Test
    void committedWalIsVisibleButNotConsideredInterrupted() throws IOException {
        var a = fixture(OWNER_A, JOURNAL_A);
        write(a, "COMMITTED");
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(1, report.committed());
        assertEquals(0, report.unfinished());
        assertFalse(report.blocksNewWrites());
    }

    @Test
    void alteredWalFailsClosedAndDoesNotFallBackToValidEarlierOne() throws IOException {
        var a = fixture(OWNER_A, JOURNAL_A);
        var b = fixture(OWNER_B, JOURNAL_B);
        Path damaged = write(a, "APPLYING");
        write(b, "ROLLED_BACK");
        Files.writeString(damaged, Files.readString(damaged).replace("APPLYING", "COMMITTED"));
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(1, report.invalid());
        assertEquals(1, report.rolledBack());
        assertTrue(report.blocksNewWrites());
        assertTrue(report.summary().contains("INVALID_WAL_OR_JOURNAL"));
    }

    @Test
    void missingBeforeImageIsNeverAcceptedAsSafeExecution() throws IOException {
        var a = fixture(OWNER_A, JOURNAL_A);
        Path wal = write(a, "APPLYING");
        Files.delete(temp.resolve("transactions").resolve(OWNER_A).resolve(JOURNAL_A + ".json"));
        assertTrue(Files.exists(wal));
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(1, report.invalid());
        assertEquals(0, report.unfinished());
        assertTrue(report.blocksNewWrites());
    }

    @Test
    void malformedOwnerAndUnexpectedFileNameAreUnsafe() throws IOException {
        Path unexpected = temp.resolve("executions").resolve("not-a-uuid");
        Files.createDirectories(unexpected);
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(1, report.invalid());
        assertTrue(report.blocksNewWrites());

        Files.createDirectories(temp.resolve("executions").resolve(OWNER_A));
        Files.writeString(temp.resolve("executions").resolve(OWNER_A).resolve("secret.txt"), "not a WAL");
        report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(2, report.invalid());
        assertTrue(report.blocksNewWrites());
    }

    @Test
    void tooManyOwnerDirectoriesBlockNewTransfersWithoutUnboundedScan() throws IOException {
        Path executions = temp.resolve("executions");
        Files.createDirectories(executions);
        for (int i = 0; i <= RelocationWatchdogStorage.MAX_OWNERS; i++) {
            Files.createDirectory(executions.resolve(String.format("dir-%04d", i)));
        }
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertTrue(report.limitReached());
        assertTrue(report.blocksNewWrites());
        assertTrue(report.total() <= RelocationWatchdogStorage.MAX_OWNERS);
    }

    @Test
    void leftoverTemporaryFilesDoNotInvalidatePreviouslyDurableRollback() throws IOException {
        var a = fixture(OWNER_A, JOURNAL_A);
        Path wal = write(a, "ROLLED_BACK");
        Files.writeString(wal.getParent().resolve("execution-unfinished.tmp"),
                "{\"phase\":\"APPLYING\"}");
        var report = RelocationWatchdogStorage.scanWorldDirectory(temp);
        assertEquals(1, report.rolledBack());
        assertEquals(0, report.invalid());
        assertFalse(report.blocksNewWrites());
    }

    private TransactionJournal fixture(String owner, String id) {
        TransactionJournal journal = new TransactionJournal();
        journal.ownerUuid = owner;
        journal.journalId = id;
        journal.sourceSnapshotId = "8bc6348c-4cc5-4cb6-bd98-a7eafaa45a23";
        journal.sourceSnapshotSha256 = "baseline";
        journal.dimension = "minecraft:overworld";
        journal.capturedAtTick = 100;
        journal.offsetX = 32;
        journal.source.minX = journal.source.maxX = 0;
        journal.source.minY = journal.source.maxY = 64;
        journal.source.minZ = journal.source.maxZ = 0;
        journal.target.minX = journal.target.maxX = 32;
        journal.target.minY = journal.target.maxY = 64;
        journal.target.minZ = journal.target.maxZ = 0;
        journal.source.before.add(new TransactionJournal.Cell(
                0, 64, 0, "minecraft:stone", "Block{minecraft:stone}", null));
        journal.target.before.add(new TransactionJournal.Cell(
                32, 64, 0, "minecraft:air", "Block{minecraft:air}", null));
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }

    private Path write(TransactionJournal journal, String phase) throws IOException {
        Path journalDir = temp.resolve("transactions").resolve(journal.ownerUuid);
        TransactionJournalStorage.saveToDirectory(journalDir, journal);
        Path wal = temp.resolve("executions").resolve(journal.ownerUuid)
                .resolve(journal.journalId + ".json");
        RelocationExecutionLedger ledger = RelocationExecutionLedger.start(journal, 2, 101);
        if ("COMMITTED".equals(phase) || "ROLLED_BACK".equals(phase)) {
            ledger.beginWrite("APPLY", 32, 64, 0, 102);
            ledger.acknowledgeWrite("APPLY", 102);
            ledger.beginWrite("APPLY", 0, 64, 0, 103);
            ledger.acknowledgeWrite("APPLY", 103);
            ledger.setPhase("COMMITTED", 104);
        }
        if ("ROLLED_BACK".equals(phase)) {
            ledger.setPhase("ROLLING_BACK", 105);
            ledger.setPhase("ROLLED_BACK", 106);
        }
        RelocationExecutionStorage.save(wal, journal, ledger, false);
        return wal;
    }
}
