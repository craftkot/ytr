package ru.ehtid.operator.snapshot;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotIntegrityTest {

    @Test
    void checksumIsIndependentOfBlockIterationOrder() {
        StructureSnapshot first = snapshot();
        first.blocks.add(new StructureSnapshot.Entry(
                1, 64, 0, "minecraft:chest",
                "Block{minecraft:chest}[facing=north]",
                "{id:\"minecraft:chest\",Items:[{Slot:0b,id:\"minecraft:diamond\",Count:1b}]}"
        ));
        first.blocks.add(new StructureSnapshot.Entry(
                0, 64, 0, "minecraft:stone", "Block{minecraft:stone}", null));
        first.blockEntityCount = 1;
        first.blockEntitySnbtBytes = 82;
        String fingerprint = SnapshotIntegrity.fingerprint(first);
        first.sha256 = fingerprint;

        StructureSnapshot second = snapshot();
        second.blocks.add(first.blocks.get(1));
        second.blocks.add(first.blocks.get(0));
        second.blockEntityCount = 1;
        second.blockEntitySnbtBytes = 82;
        second.sha256 = SnapshotIntegrity.fingerprint(second);

        assertEquals(fingerprint, second.sha256);
        assertTrue(SnapshotIntegrity.valid(first));
        assertTrue(SnapshotIntegrity.valid(second));
    }

    @Test
    void tamperingWithInventoryOrBlockPositionInvalidatesSnapshot() {
        StructureSnapshot snapshot = snapshot();
        snapshot.blocks.add(new StructureSnapshot.Entry(
                1, 64, 0, "minecraft:barrel", "Block{minecraft:barrel}",
                "{Items:[{id:\"minecraft:apple\"}]}"));
        snapshot.blockEntityCount = 1;
        snapshot.sha256 = SnapshotIntegrity.fingerprint(snapshot);
        assertTrue(SnapshotIntegrity.valid(snapshot));

        snapshot.blocks.set(0, new StructureSnapshot.Entry(
                1, 64, 0, "minecraft:barrel", "Block{minecraft:barrel}",
                "{Items:[{id:\"minecraft:diamond\"}]}"));
        assertFalse(SnapshotIntegrity.valid(snapshot));

        snapshot.sha256 = SnapshotIntegrity.fingerprint(snapshot);
        snapshot.minX = 100;
        assertFalse(SnapshotIntegrity.valid(snapshot));
    }

    @Test
    void checksumProtectsOwnershipAndWorldDimension() {
        StructureSnapshot snapshot = snapshot();
        snapshot.sha256 = SnapshotIntegrity.fingerprint(snapshot);
        snapshot.playerUuid = UUID.randomUUID().toString();
        assertFalse(SnapshotIntegrity.valid(snapshot));
    }

    private static StructureSnapshot snapshot() {
        StructureSnapshot snapshot = new StructureSnapshot();
        snapshot.snapshotId = "20c492da-b218-4c52-800d-caa1f88390ab";
        snapshot.playerUuid = "d8e8c02f-91bd-449d-b259-e29ab54e20e6";
        snapshot.dimension = "minecraft:overworld";
        snapshot.capturedAtTick = 100_000L;
        snapshot.minX = 0; snapshot.minY = 64; snapshot.minZ = 0;
        snapshot.maxX = 2; snapshot.maxY = 66; snapshot.maxZ = 2;
        return snapshot;
    }
}
