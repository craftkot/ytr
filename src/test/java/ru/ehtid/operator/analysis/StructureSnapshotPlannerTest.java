package ru.ehtid.operator.analysis;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class StructureSnapshotPlannerTest {
    private static final String WORLD = "minecraft:overworld";

    @Test
    void selectsNearestConnectedBuildingAndDoesNotCombineOtherDimension() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        box(blocks, 0, 64, 0, WORLD);
        box(blocks, 80, 64, 0, WORLD);
        box(blocks, 0, 64, 0, "minecraft:the_nether");

        var selected = StructureSnapshotPlanner.select(blocks, WORLD, 82, 65, 2);

        assertNotNull(selected);
        assertEquals(80, selected.minX());
        assertEquals(26, selected.blockCount());
        assertFalse(selected.oversized());
        assertEquals(WORLD, selected.dimension());
    }

    @Test
    void skipsSparseAndDistantStructures() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    x, 64, 0, WORLD, "minecraft:stone", x
            ));
        }
        assertNull(StructureSnapshotPlanner.select(blocks, WORLD, 2, 64, 0));
        blocks.clear();
        box(blocks, 200, 64, 0, WORLD);
        assertNull(StructureSnapshotPlanner.select(blocks, WORLD, 0, 64, 0));
    }

    @Test
    void oversizedComponentsAreRejectedInsteadOfSilentlyTruncated() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = 0; z < 20; z++) {
                    blocks.add(new PlayerMemory.ConstructionBlock(
                            x, 64 + y, z, WORLD, "minecraft:stone", blocks.size()
                    ));
                }
            }
        }
        var selected = StructureSnapshotPlanner.select(blocks, WORLD, 1, 65, 1);
        assertNotNull(selected);
        assertTrue(selected.oversized());
        assertTrue(selected.blocks().isEmpty());
    }

    @Test
    void preflightChecksAirAndLoadedChunksWithoutMutations() {
        var selected = selectedBox();
        RecordingInspector inspector = new RecordingInspector();
        var result = StructureSnapshotPlanner.translation(
                selected, 70, 64, 0, -64, 320, inspector);
        assertTrue(result.eligible());
        assertEquals("PREVIEW_ONLY", result.reason());
        assertEquals(26, result.checkedBlocks());
        assertEquals(26, inspector.emptyChecks);
    }

    @Test
    void rejectsSourceOverlapAndMissingDestinationChunks() {
        var selected = selectedBox();
        var overlap = StructureSnapshotPlanner.translation(
                selected, 1, 64, 0, -64, 320, new RecordingInspector());
        assertFalse(overlap.eligible());
        assertEquals("SOURCE_DESTINATION_OVERLAP", overlap.reason());

        RecordingInspector inspector = new RecordingInspector();
        inspector.unloaded = true;
        var notLoaded = StructureSnapshotPlanner.translation(
                selected, 70, 64, 0, -64, 320, inspector);
        assertFalse(notLoaded.eligible());
        assertEquals("UNLOADED_DESTINATION", notLoaded.reason());
    }

    @Test
    void rejectsCollisionAndOutOfRangeDestinations() {
        var selected = selectedBox();
        RecordingInspector inspector = new RecordingInspector();
        inspector.occupied = true;
        var collision = StructureSnapshotPlanner.translation(
                selected, 70, 64, 0, -64, 320, inspector);
        assertEquals("DESTINATION_OCCUPIED", collision.reason());

        var tooHigh = StructureSnapshotPlanner.translation(
                selected, 70, 319, 0, -64, 320, new RecordingInspector());
        assertEquals("OUT_OF_BUILD_HEIGHT", tooHigh.reason());

        var tooFar = StructureSnapshotPlanner.translation(
                selected, 5000, 64, 0, -64, 320, new RecordingInspector());
        assertEquals("TRANSLATION_LIMIT", tooFar.reason());
    }

    private static StructureSnapshotPlanner.Selection selectedBox() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        box(blocks, 0, 64, 0, WORLD);
        return StructureSnapshotPlanner.select(blocks, WORLD, 1, 65, 1);
    }

    private static void box(List<PlayerMemory.ConstructionBlock> blocks,
                            int bx, int by, int bz, String dimension) {
        for (int x = 0; x <= 2; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = 0; z <= 2; z++) {
                    if (x != 0 && x != 2 && y != 0 && y != 2 && z != 0 && z != 2) continue;
                    blocks.add(new PlayerMemory.ConstructionBlock(
                            bx + x, by + y, bz + z, dimension,
                            "minecraft:stone_bricks", blocks.size()));
                }
            }
        }
    }

    private static final class RecordingInspector
            implements StructureSnapshotPlanner.DestinationInspector {
        int emptyChecks = 0;
        boolean unloaded;
        boolean occupied;

        @Override
        public boolean loaded(int x, int y, int z) {
            return !unloaded;
        }

        @Override
        public boolean empty(int x, int y, int z) {
            emptyChecks++;
            return !occupied;
        }
    }
}
