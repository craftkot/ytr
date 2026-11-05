package ru.ehtid.operator.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class ConstructionAnalyzerTest {
    @Test
    void detectsConnectedThreeDimensionalBuildingCandidate() {
        PlayerMemory memory = new PlayerMemory();
        long tick = 100L;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2) {
                        memory.recordPlacedBlock(x, y, z, "minecraft:overworld",
                                "minecraft:oak_planks", tick++);
                    }
                }
            }
        }

        ConstructionAnalyzer.Analysis analysis =
                ConstructionAnalyzer.analyze(memory.constructionBlocks.values());

        assertEquals(26, analysis.trackedBlockCount());
        assertEquals(1, analysis.structureCount());
        assertEquals(1, analysis.buildingCandidateCount());
        ConstructionAnalyzer.Structure building = analysis.structures().get(0);
        assertTrue(building.buildingCandidate());
        assertEquals(3, building.spanX());
        assertEquals(3, building.spanY());
        assertEquals(3, building.spanZ());
        assertEquals(26, building.blockCount());
        assertTrue(building.confidence() > 0.35D);
        assertTrue(building.enclosedBuildingCandidate());
        assertEquals("ENCLOSED_BUILDING", building.kind());
        assertEquals(1, building.enclosedVoidCells());
        assertEquals(1.0D, building.floorCoverage(), 0.0001D);
        assertEquals(1.0D, building.roofCoverage(), 0.0001D);
        assertEquals(1.0D, building.wallCoverage(), 0.0001D);
    }

    @Test
    void recognizesDoorsWindowsStairsAndSlabsInAnEnclosedShell() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        long tick = 0L;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 5; z++) {
                    if (x == 0 || x == 4 || y == 0 || y == 3 || z == 0 || z == 4) {
                        String blockId = "minecraft:stone_bricks";
                        String state = "Block{minecraft:stone_bricks}";
                        if (x == 2 && z == 0 && y == 1) {
                            blockId = "minecraft:oak_door";
                            state = "Block{minecraft:oak_door}[facing=north,half=lower,hinge=left,open=false]";
                        } else if (x == 2 && z == 0 && y == 2) {
                            blockId = "minecraft:oak_door";
                            state = "Block{minecraft:oak_door}[facing=north,half=upper,hinge=left,open=false]";
                        } else if (x == 1 && z == 0 && y == 1) {
                            blockId = "minecraft:glass_pane";
                            state = "Block{minecraft:glass_pane}[north=true,south=true]";
                        }
                        blocks.add(new PlayerMemory.ConstructionBlock(
                                x, y, z, "minecraft:overworld", blockId, state, tick++
                        ));
                    }
                }
            }
        }
        blocks.add(new PlayerMemory.ConstructionBlock(
                1, 1, 1, "minecraft:overworld", "minecraft:oak_stairs",
                "Block{minecraft:oak_stairs}[facing=east,half=bottom,shape=straight]", tick++
        ));
        blocks.add(new PlayerMemory.ConstructionBlock(
                3, 1, 1, "minecraft:overworld", "minecraft:oak_slab",
                "Block{minecraft:oak_slab}[type=bottom,waterlogged=false]", tick
        ));

        ConstructionAnalyzer.Structure structure =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(1, structure.doorCount(), "upper and lower door halves form one door");
        assertEquals(1, structure.windowBlockCount());
        assertEquals(1, structure.stairBlockCount());
        assertEquals(1, structure.slabBlockCount());
        assertEquals(16, structure.enclosedVoidCells(),
                "two interior decorative blocks occupy two of the original 18 void cells");
        assertTrue(structure.enclosedBuildingCandidate());
        assertTrue(structure.uniqueBlockStates() >= 4);
        assertEquals(0, structure.floorLayerCount(),
                "perimeter walls must not be mistaken for internal floors");
    }

    @Test
    void detectsOpenRoofAsBuildingCandidateButDoesNotClaimEnclosedInterior() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        long tick = 0L;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 5; z++) {
                    if (x == 0 || x == 4 || y == 0 || y == 3 || z == 0 || z == 4) {
                        if (x == 2 && y == 3 && z == 2) continue;
                        blocks.add(new PlayerMemory.ConstructionBlock(
                                x, y, z, "minecraft:overworld", "minecraft:stone_bricks", tick++
                        ));
                    }
                }
            }
        }

        ConstructionAnalyzer.Structure structure =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertTrue(structure.buildingCandidate());
        assertTrue(structure.interiorScanComplete());
        assertEquals(0, structure.enclosedVoidCells());
        assertFalse(structure.enclosedBuildingCandidate());
        assertEquals("OPEN_BUILDING", structure.kind());
    }

    @Test
    void capsInteriorScanForLargeBoundingBoxes() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        long tick = 0L;
        for (int x = 0; x <= 32; x++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    x, 0, 0, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }
        for (int y = 1; y <= 32; y++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    32, y, 0, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }
        for (int z = 1; z <= 32; z++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    32, 32, z, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }

        ConstructionAnalyzer.Structure structure =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertTrue(structure.buildingCandidate());
        assertFalse(structure.interiorScanComplete());
        assertEquals(0, structure.enclosedVoidCells());
    }

    @Test
    void countsInternalFloorLayersWithoutCountingWallPerimeters() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        long tick = 0L;
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 5; z++) {
                    if (x == 0 || x == 4 || y == 0 || y == 4 || z == 0 || z == 4
                            || (y == 2 && x > 0 && x < 4 && z > 0 && z < 4)) {
                        blocks.add(new PlayerMemory.ConstructionBlock(
                                x, y, z, "minecraft:overworld", "minecraft:stone_bricks", tick++
                        ));
                    }
                }
            }
        }

        ConstructionAnalyzer.Structure structure =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(1, structure.floorLayerCount());
    }


    @Test
    void solidFilledCubeIsNotMistakenForAnOpenBuilding() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        long tick = 0L;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    blocks.add(new PlayerMemory.ConstructionBlock(
                            x, y, z, "minecraft:overworld", "minecraft:stone", tick++
                    ));
                }
            }
        }

        ConstructionAnalyzer.Structure structure =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertTrue(structure.buildingCandidate());
        assertTrue(structure.interiorScanComplete());
        assertEquals(0, structure.unoccupiedCells());
        assertEquals(0, structure.enclosedVoidCells());
        assertEquals("BUILDING_CANDIDATE", structure.kind());
    }

    @Test
    void doesNotClassifyThinPillarAsBuilding() {
        PlayerMemory memory = new PlayerMemory();
        for (int y = 0; y < 40; y++) {
            memory.recordPlacedBlock(0, y, 0, "minecraft:overworld",
                    "minecraft:cobblestone", y);
        }

        ConstructionAnalyzer.Analysis analysis =
                ConstructionAnalyzer.analyze(memory.constructionBlocks.values());

        assertEquals(1, analysis.structureCount());
        assertEquals(0, analysis.buildingCandidateCount());
        assertEquals(40, analysis.largestStructureBlockCount());
        assertEquals(0, analysis.largestBuildingBlockCount());
    }

    @Test
    void separatesDisconnectedComponentsAndDimensions() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        addCube(blocks, "minecraft:overworld", 0, 0, 0);
        addCube(blocks, "minecraft:the_nether", 0, 0, 0);

        ConstructionAnalyzer.Analysis all = ConstructionAnalyzer.analyze(blocks);
        ConstructionAnalyzer.Analysis overworld =
                ConstructionAnalyzer.analyze(blocks, "minecraft:overworld");

        assertEquals(2, all.structureCount());
        assertEquals(8, all.largestStructureBlockCount());
        assertEquals(1, overworld.structureCount());
        assertEquals(8, overworld.trackedBlockCount());
    }

    @Test
    void ignoresSmallScatteredPlacementNoise() {
        PlayerMemory memory = new PlayerMemory();
        for (int i = 0; i < 7; i++) {
            memory.recordPlacedBlock(i * 3, 64, 0, "minecraft:overworld",
                    "minecraft:torch", i);
        }

        ConstructionAnalyzer.Analysis analysis =
                ConstructionAnalyzer.analyze(memory.constructionBlocks.values());

        assertEquals(7, analysis.trackedBlockCount());
        assertEquals(0, analysis.structureCount());
        assertEquals(0, analysis.buildingCandidateCount());
    }

    @Test
    void sameCoordinatesInDifferentDimensionsNeverConnect() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    i, 64, 0, "minecraft:overworld", "minecraft:stone", i
            ));
            blocks.add(new PlayerMemory.ConstructionBlock(
                    i, 64, 0, "minecraft:the_nether", "minecraft:stone", i
            ));
        }

        ConstructionAnalyzer.Analysis analysis = ConstructionAnalyzer.analyze(blocks);
        assertEquals(2, analysis.structureCount());
        assertTrue(analysis.structures().stream()
                .allMatch(structure -> structure.blockCount() == 8));
    }


    @Test
    void capsAggregateInteriorWorkAcrossManyLargeComponents() {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        addLargeConnectedPath(blocks, 0);
        addLargeConnectedPath(blocks, 100);
        addLargeConnectedPath(blocks, 200);

        ConstructionAnalyzer.Analysis analysis = ConstructionAnalyzer.analyze(blocks);

        assertEquals(3, analysis.structureCount());
        assertEquals(2, analysis.structures().stream()
                .filter(ConstructionAnalyzer.Structure::interiorScanComplete).count());
        assertFalse(analysis.structures().get(2).interiorScanComplete());
    }

    private static void addLargeConnectedPath(
            List<PlayerMemory.ConstructionBlock> blocks, int offset
    ) {
        long tick = blocks.size();
        for (int x = 0; x <= 31; x++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    offset + x, 0, 0, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }
        for (int y = 1; y <= 31; y++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    offset + 31, y, 0, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }
        for (int z = 1; z <= 31; z++) {
            blocks.add(new PlayerMemory.ConstructionBlock(
                    offset + 31, 31, z, "minecraft:overworld", "minecraft:stone", tick++
            ));
        }
    }

    private static void addCube(List<PlayerMemory.ConstructionBlock> blocks,
                                String dimension, int ox, int oy, int oz) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) {
                    blocks.add(new PlayerMemory.ConstructionBlock(
                            ox + x, oy + y, oz + z, dimension, "minecraft:stone", blocks.size()
                    ));
                }
            }
        }
    }
}
