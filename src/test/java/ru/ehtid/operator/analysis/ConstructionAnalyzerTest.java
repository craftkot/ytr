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
