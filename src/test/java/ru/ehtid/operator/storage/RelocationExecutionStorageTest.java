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

class RelocationExecutionStorageTest {
    @TempDir Path temp;

    @Test
    void writeAheadFileSurvivesRestartsAndAtomicReplacements() throws IOException {
        TransactionJournal j = journal();
        Path path = temp.resolve("owner").resolve("write-ahead.json");
        RelocationExecutionLedger state = RelocationExecutionLedger.start(j, 2, 100);
        RelocationExecutionStorage.save(path, j, state, false);
        assertThrows(IOException.class,
                () -> RelocationExecutionStorage.save(path, j, state, false));

        state.beginWrite("APPLY", 10, 64, 0, 101);
        RelocationExecutionStorage.save(path, j, state, true);
        RelocationExecutionLedger restarted = RelocationExecutionStorage.read(path, j);
        assertEquals("APPLY", restarted.pendingOperation);
        assertEquals(10, restarted.pendingX);

        restarted.beginRollbackAfterInterruptedWrite(102);
        RelocationExecutionStorage.save(path, j, restarted, true);
        RelocationExecutionLedger restartedAgain = RelocationExecutionStorage.read(path, j);
        assertEquals("ROLLING_BACK", restartedAgain.phase);
        assertTrue(restartedAgain.matches(j));
    }

    @Test
    void corruptedLedgerIsNeverTrustedOrOverwrittenWithoutIntegrity() throws IOException {
        TransactionJournal j = journal();
        Path path = temp.resolve("test.json");
        RelocationExecutionLedger state = RelocationExecutionLedger.start(j, 2, 100);
        RelocationExecutionStorage.save(path, j, state, false);
        Files.writeString(path, Files.readString(path).replace("APPLYING", "COMMITTED"));
        assertThrows(IOException.class, () -> RelocationExecutionStorage.read(path, j));

        state.beginWrite("APPLY", 10, 64, 0, 101);
        // A bad in-memory state is also refused, even if the file exists.
        state.pendingX = 9000;
        assertThrows(IOException.class,
                () -> RelocationExecutionStorage.save(path, j, state, true));
    }

    @Test
    void journalSubstitutionFailsEvenIfLedgerFileExists() throws IOException {
        TransactionJournal j = journal();
        Path path = temp.resolve("owner").resolve("write-ahead.json");
        RelocationExecutionLedger state = RelocationExecutionLedger.start(j, 2, 100);
        RelocationExecutionStorage.save(path, j, state, false);

        TransactionJournal other = journal();
        other.journalId = "b0ce73b9-2805-4684-b559-0a7f9d3cb3c2";
        other.sha256 = TransactionIntegrity.fingerprint(other);
        assertThrows(IOException.class, () -> RelocationExecutionStorage.read(path, other));
    }

    private static TransactionJournal journal() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "01893b3c-a2a3-426b-918f-5783e40445b9";
        j.ownerUuid = "4b1feb57-45ba-4bd3-8c08-161b6ecb7209";
        j.sourceSnapshotId = "a4409be9-a62a-458d-9416-3be0c25b313e";
        j.sourceSnapshotSha256 = "test";
        j.dimension = "minecraft:overworld";
        j.offsetX = 10;
        j.source.minX = j.source.maxX = 0;
        j.source.minY = j.source.maxY = 64;
        j.source.minZ = j.source.maxZ = 0;
        j.target.minX = j.target.maxX = 10;
        j.target.minY = j.target.maxY = 64;
        j.target.minZ = j.target.maxZ = 0;
        j.source.before.add(new TransactionJournal.Cell(
                0, 64, 0, "minecraft:stone", "Block{minecraft:stone}", null));
        j.target.before.add(new TransactionJournal.Cell(
                10, 64, 0, "minecraft:air", "Block{minecraft:air}", null));
        j.sha256 = TransactionIntegrity.fingerprint(j);
        return j;
    }
}
