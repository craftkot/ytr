package ru.ehtid.operator.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ehtid.operator.transaction.TransactionIntegrity;
import ru.ehtid.operator.transaction.TransactionJournal;
import static org.junit.jupiter.api.Assertions.*;

class TransactionJournalStorageTest {
    @TempDir Path temp;

    @Test
    void preparedJournalCanBeWrittenAndReloaded() throws IOException {
        TransactionJournal journal = journal();
        Path file = TransactionJournalStorage.saveToDirectory(temp, journal);
        assertTrue(Files.isRegularFile(file));
        TransactionJournal loaded = TransactionJournalStorage.read(
                file, journal.ownerUuid, journal.journalId);
        assertEquals(journal.sha256, loaded.sha256);
        assertThrows(IOException.class,
                () -> TransactionJournalStorage.saveToDirectory(temp, journal));
    }

    private static TransactionJournal journal() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "c52d8126-b6b4-48f4-b9e9-c04d90a3d3d7";
        j.ownerUuid = "e1a28d1d-68c1-4a5a-bc9a-b9f4c2b6f25e";
        j.sourceSnapshotId = "d2b07e4d-69a2-49c4-8c4f-2a1a1f13a2d0";
        j.sourceSnapshotSha256 = "test-snapshot";
        j.dimension = "minecraft:overworld";
        j.capturedAtTick = 400;
        j.offsetX = 10;
        j.source.minX = 0; j.source.maxX = 1;
        j.source.minY = 64; j.source.maxY = 65;
        j.source.minZ = 0; j.source.maxZ = 1;
        j.target.minX = 10; j.target.maxX = 11;
        j.target.minY = 64; j.target.maxY = 65;
        j.target.minZ = 0; j.target.maxZ = 1;
        for (int x = 0; x <= 1; x++)
            for (int y = 64; y <= 65; y++)
                for (int z = 0; z <= 1; z++) {
                    j.source.before.add(new TransactionJournal.Cell(
                            x, y, z, "minecraft:stone", "Block{minecraft:stone}", null));
                    j.target.before.add(new TransactionJournal.Cell(
                            x + 10, y, z, "minecraft:air", "Block{minecraft:air}", null));
                }
        j.sha256 = TransactionIntegrity.fingerprint(j);
        return j;
    }
}
