package ru.ehtid.operator.transaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RelocationHazardAnalyzerTest {
    @Test
    void classifiesHighRiskBlockFamiliesWithoutWorldAccess() {
        assertEquals(RelocationHazardAnalyzer.Category.ORDINARY,
                RelocationHazardAnalyzer.classify("minecraft:oak_planks", "Block{minecraft:oak_planks}"));
        assertEquals(RelocationHazardAnalyzer.Category.FLUID,
                RelocationHazardAnalyzer.classify("minecraft:water", "Block{minecraft:water}"));
        assertEquals(RelocationHazardAnalyzer.Category.FLUID,
                RelocationHazardAnalyzer.classify("minecraft:oak_slab", "[waterlogged=true]"));
        assertEquals(RelocationHazardAnalyzer.Category.GRAVITY,
                RelocationHazardAnalyzer.classify("minecraft:gravel", "Block{minecraft:gravel}"));
        assertEquals(RelocationHazardAnalyzer.Category.REDSTONE,
                RelocationHazardAnalyzer.classify("minecraft:sticky_piston", "Block{minecraft:sticky_piston}"));
        assertEquals(RelocationHazardAnalyzer.Category.ATTACHED,
                RelocationHazardAnalyzer.classify("minecraft:oak_door", "[half=upper]"));
        assertEquals(RelocationHazardAnalyzer.Category.MODDED,
                RelocationHazardAnalyzer.classify("techmod:machine", "[facing=north]"));
        assertEquals(RelocationHazardAnalyzer.Category.UNKNOWN,
                RelocationHazardAnalyzer.classify("minecraft:unrecognized_special_block", ""));
    }

    @Test
    void countsSourceAndDestinationRiskWithoutExposingNbt() {
        TransactionJournal j = new TransactionJournal();
        j.journalId = "f1245678-f012-45b7-9d10-cf05c4a77ae5";
        j.ownerUuid = "bd155938-48a7-4406-a55c-2465b14a7871";
        j.sourceSnapshotId = "0e4a3a75-663e-4faf-a90b-cf8cdb1b2341";
        j.sourceSnapshotSha256 = "snapshot-id";
        j.dimension = "minecraft:overworld";
        j.source.minX = 0; j.source.maxX = 2;
        j.source.minY = j.source.maxY = 64;
        j.source.minZ = j.source.maxZ = 0;
        j.target.minX = 10; j.target.maxX = 12;
        j.target.minY = j.target.maxY = 64;
        j.target.minZ = j.target.maxZ = 0;
        j.offsetX = 10;
        j.source.before.add(new TransactionJournal.Cell(
                0, 64, 0, "minecraft:chest", "Block{minecraft:chest}", "{Items:[]}"));
        j.source.before.add(new TransactionJournal.Cell(
                1, 64, 0, "minecraft:lava", "Block{minecraft:lava}", null));
        j.source.before.add(new TransactionJournal.Cell(
                2, 64, 0, "minecraft:observer", "Block{minecraft:observer}", null));
        for (int x = 10; x <= 12; x++) {
            j.target.before.add(new TransactionJournal.Cell(
                    x, 64, 0, "minecraft:air", "Block{minecraft:air}", null));
        }
        j.blockEntityCount = 1;
        j.sha256 = TransactionIntegrity.fingerprint(j);

        var risk = RelocationHazardAnalyzer.inspect(j);
        assertEquals(6, risk.cells());
        assertEquals(1, risk.blockEntities());
        assertEquals(1, risk.fluids());
        assertEquals(1, risk.redstoneDevices());
        assertEquals(1, risk.otherUnknown()); // Chest itself requires separate BE support.
        assertTrue(risk.requiresManualReview());
        assertFalse(risk.summary().contains("Items:"));
    }
}
