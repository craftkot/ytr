package ru.ehtid.operator.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ehtid.operator.transaction.RecoveryCheckpointLedger;
import ru.ehtid.operator.transaction.RecoveryPlanner;
import ru.ehtid.operator.transaction.TransactionIntegrity;
import ru.ehtid.operator.transaction.TransactionJournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryCheckpointStorageTest {

    @TempDir Path tempDir;

    @Test
    void persistsAndReloadsVerifiedCheckpointAfterSimulatedServerRestart()
            throws IOException {
        TransactionJournal journal = journal();
        Path file = tempDir.resolve("player").resolve(journal.journalId + ".json");
        RecoveryCheckpointLedger checkpoint =
                RecoveryCheckpointLedger.initialize(journal, 100L);

        RecoveryCheckpointStorage.writeCheckpoint(file, checkpoint, false);
        RecoveryCheckpointLedger restored =
                RecoveryCheckpointStorage.readCheckpoint(file, journal);
        assertTrue(restored.valid());
        assertEquals("PREPARED", restored.latest().phase());

        restored.appendAudit(journal, 101L, new RecoveryPlanner.Plan(
                RecoveryPlanner.Status.RESTORE_NEEDED, 4, 2, 2, 0, 0, List.of()));
        RecoveryCheckpointStorage.writeCheckpoint(file, restored, true);

        RecoveryCheckpointLedger afterAnotherRestart =
                RecoveryCheckpointStorage.readCheckpoint(file, journal);
        assertEquals(2, afterAnotherRestart.checkpoints.size());
        assertEquals(1, afterAnotherRestart.latest().sequence());
        assertEquals("RESTORE_NEEDED", afterAnotherRestart.latest().phase());
        assertEquals(restored.latest().hash(), afterAnotherRestart.latest().hash());
    }

    @Test
    void refusesToOverwriteExistingPreparedFile() throws IOException {
        TransactionJournal journal = journal();
        Path file = tempDir.resolve("checkpoint.json");
        RecoveryCheckpointLedger checkpoint =
                RecoveryCheckpointLedger.initialize(journal, 100L);
        RecoveryCheckpointStorage.writeCheckpoint(file, checkpoint, false);
        String before = Files.readString(file);
        assertThrows(IOException.class,
                () -> RecoveryCheckpointStorage.writeCheckpoint(file, checkpoint, false));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void corruptedOrTruncatedCheckpointIsNeverAccepted() throws IOException {
        TransactionJournal journal = journal();
        Path file = tempDir.resolve("checkpoint.json");
        RecoveryCheckpointLedger checkpoint =
                RecoveryCheckpointLedger.initialize(journal, 100L);
        RecoveryCheckpointStorage.writeCheckpoint(file, checkpoint, false);

        String content = Files.readString(file);
        assertTrue(content.contains("PREPARED"));
        Files.writeString(file, content.replace("PREPARED", "CONFLICT"));
        assertThrows(IOException.class,
                () -> RecoveryCheckpointStorage.readCheckpoint(file, journal));

        Files.writeString(file, "{\"checkpoint", StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> RecoveryCheckpointStorage.readCheckpoint(file, journal));
    }

    @Test
    void wrongOwnerOrJournalBindingFailsClosed() throws IOException {
        TransactionJournal journal = journal();
        Path file = tempDir.resolve("checkpoint.json");
        RecoveryCheckpointStorage.writeCheckpoint(file,
                RecoveryCheckpointLedger.initialize(journal, 100L), false);

        TransactionJournal other = journal();
        other.ownerUuid = "20f50638-f1e9-4a68-936c-76d821e038e5";
        other.sha256 = TransactionIntegrity.fingerprint(other);
        assertThrows(IOException.class,
                () -> RecoveryCheckpointStorage.readCheckpoint(file, other));
    }

    private static TransactionJournal journal() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "02d4d02c-1f74-4b70-9c4b-685b3a26ac9f";
        j.ownerUuid = "ba586ce8-e7d0-4c1b-84fc-00ead88577d1";
        j.sourceSnapshotId = "3b56818f-2b18-4fa2-95b2-87a5468018e1";
        j.sourceSnapshotSha256 = "original";
        j.dimension = "minecraft:overworld";
        j.offsetX = 10;
        j.capturedAtTick = 100L;
        j.source.minX = 0; j.source.maxX = 1;
        j.source.minY = j.source.maxY = 64;
        j.source.minZ = j.source.maxZ = 0;
        j.target.minX = 10; j.target.maxX = 11;
        j.target.minY = j.target.maxY = 64;
        j.target.minZ = j.target.maxZ = 0;
        for (int x = 0; x < 2; x++) {
            j.source.before.add(new TransactionJournal.Cell(
                    x, 64, 0, "minecraft:stone", "Block{minecraft:stone}", null));
            j.target.before.add(new TransactionJournal.Cell(
                    x + 10, 64, 0, "minecraft:air", "Block{minecraft:air}", null));
        }
        j.sha256 = TransactionIntegrity.fingerprint(j);
        return j;
    }
}
