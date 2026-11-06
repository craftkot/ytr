package ru.ehtid.operator.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionJournalTest {
    @Test
    void fullRegionPreimagesProduceDeterministicDigest() {
        TransactionJournal first = validJournal();
        first.sha256 = TransactionIntegrity.fingerprint(first);
        assertTrue(TransactionIntegrity.valid(first));
        TransactionJournal second = validJournal();
        Collections.reverse(second.source.before);
        Collections.reverse(second.target.before);
        assertEquals(first.sha256, TransactionIntegrity.fingerprint(second));
        second.sha256 = first.sha256;
        assertTrue(TransactionIntegrity.valid(second));
    }

    @Test
    void detectsTamperingWithBlockEntityData() {
        TransactionJournal journal = validJournal();
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        var initial = journal.source.before.get(0);
        journal.source.before.set(0, new TransactionJournal.Cell(
                initial.x(), initial.y(), initial.z(),
                initial.blockId(), initial.blockState(),
                "{Items:[{id:\"minecraft:diamond\"}]}"));
        assertFalse(TransactionIntegrity.valid(journal));
    }

    @Test
    void refusesMissingOrDuplicateCells() {
        TransactionJournal journal = validJournal();
        journal.source.before.remove(0);
        assertFalse(TransactionJournal.hasCompleteDisjointPreimages(journal));
        assertThrows(IllegalArgumentException.class,
                () -> TransactionIntegrity.fingerprint(journal));

        TransactionJournal duplicate = validJournal();
        duplicate.source.before.set(1, duplicate.source.before.get(0));
        assertFalse(TransactionJournal.hasCompleteDisjointPreimages(duplicate));
    }

    @Test
    void refusesOverlappingAabbs() {
        TransactionJournal journal = validJournal();
        journal.target.minX = journal.source.minX;
        journal.target.maxX = journal.source.maxX;
        journal.target.minY = journal.source.minY;
        journal.target.maxY = journal.source.maxY;
        journal.target.minZ = journal.source.minZ;
        journal.target.maxZ = journal.source.maxZ;
        assertFalse(TransactionJournal.hasCompleteDisjointPreimages(journal));
    }

    @Test
    void producesCompleteUniqueReversePreimageSet() {
        TransactionJournal journal = validJournal();
        List<TransactionJournal.Cell> reverse =
                TransactionJournal.reversePreimages(journal);
        assertEquals(16, reverse.size());
        Set<String> positions = new HashSet<>();
        for (TransactionJournal.Cell cell : reverse) {
            assertTrue(positions.add(cell.x() + "," + cell.y() + "," + cell.z()));
        }
        assertEquals(16, positions.size());
        assertEquals(0, reverse.get(0).x());
        assertEquals(11, reverse.get(reverse.size() - 1).x());
    }

    @Test
    void detectsUnauthorizedMetadataChange() {
        TransactionJournal journal = validJournal();
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        journal.phase = "COMMITTED";
        assertFalse(TransactionIntegrity.valid(journal));
        journal.phase = "PREPARED";
        journal.target.before.set(0, new TransactionJournal.Cell(
                10, 64, 0, "minecraft:stone", "Block{minecraft:stone}", null));
        assertFalse(TransactionIntegrity.valid(journal));
    }

    @Test
    void refusesExcessiveVolumeWithoutIteratingWorld() {
        TransactionJournal journal = validJournal();
        journal.source.maxX = 10_000;
        assertFalse(TransactionJournal.hasCompleteDisjointPreimages(journal));
    }

    private static TransactionJournal validJournal() {
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = "e01758ab-8843-4ddd-993e-d4ce8a70393b";
        journal.ownerUuid = "10bf24fa-8e43-496a-a290-9c9c6fafc15e";
        journal.sourceSnapshotId = UUID.randomUUID().toString();
        // Source snapshot ID is part of the checksum; override for order comparison.
        journal.sourceSnapshotId = "91191f62-c782-4828-9809-53e0db87e253";
        journal.sourceSnapshotSha256 = "abc123";
        journal.dimension = "minecraft:overworld";
        journal.capturedAtTick = 40_000L;
        journal.offsetX = 10;
        journal.source.minX = 0;
        journal.source.maxX = 1;
        journal.source.minY = 64;
        journal.source.maxY = 65;
        journal.source.minZ = 0;
        journal.source.maxZ = 1;
        journal.target.minX = 10;
        journal.target.maxX = 11;
        journal.target.minY = 64;
        journal.target.maxY = 65;
        journal.target.minZ = 0;
        journal.target.maxZ = 1;
        for (int x=0; x<=1; x++) {
            for (int y=64; y<=65; y++) {
                for (int z=0; z<=1; z++) {
                    journal.source.before.add(new TransactionJournal.Cell(
                            x, y, z, "minecraft:stone",
                            "Block{minecraft:stone}", null));
                    journal.target.before.add(new TransactionJournal.Cell(
                            x+10, y, z, "minecraft:air", "Block{minecraft:air}", null));
                }
            }
        }
        return journal;
    }
}
