package ru.ehtid.operator.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class PlayerMemoryTest {
    @Test
    void personalObservationCountsOnlyActiveTicks() {
        PlayerMemory memory = new PlayerMemory();
        for (int i = 0; i < 23_999; i++) memory.recordActiveTick(i);
        assertFalse(memory.personalObservationComplete(24_000));
        memory.recordActiveTick(24_000);
        assertTrue(memory.personalObservationComplete(24_000));
        assertEquals(24_000, memory.observedTicks);
    }

    @Test
    void movementAccumulatesSmallSegmentsAndSamplesMeaningfulRoute() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordMovement(0, 64, 0, "minecraft:overworld", 0);
        memory.recordMovement(1, 64, 0, "minecraft:overworld", 100);
        memory.recordMovement(2, 64, 0, "minecraft:overworld", 200);
        memory.recordMovement(3, 64, 0, "minecraft:overworld", 300);

        assertEquals(3.0D, memory.distanceTravelled, 0.0001D);
        assertEquals(4, memory.movementSamples);
        assertEquals(4, memory.route.size());
    }

    @Test
    void dimensionChangeAddsRoutePointWithoutInventingDistance() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordMovement(0, 64, 0, "minecraft:overworld", 0);
        memory.recordMovement(100, 64, 100, "minecraft:the_nether", 100);

        assertEquals(0.0D, memory.distanceTravelled, 0.0001D);
        assertEquals(2, memory.route.size());
        assertEquals("minecraft:the_nether", memory.lastDimension);
    }

    @Test
    void adjacentMiningBlocksBuildChainAndDirection() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(0, -20, 0, "minecraft:overworld", "minecraft:stone", true, 10);
        memory.recordBrokenBlock(1, -20, 0, "minecraft:overworld", "minecraft:stone", true, 20);
        memory.recordBrokenBlock(2, -20, 0, "minecraft:overworld", "minecraft:deepslate", true, 30);

        assertEquals(3, memory.currentMiningChain);
        assertEquals(3, memory.longestMiningChain);
        assertEquals(1, memory.miningSessions);
        assertEquals("east", memory.dominantMiningDirection());
        assertEquals("deep_minus1_minus32", memory.miningDepthBands.keySet().iterator().next());
        assertEquals("minecraft:stone", memory.mostMinedBlockType());
    }

    @Test
    void miningDimensionChangeStartsNewChain() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(0, 20, 0, "minecraft:overworld", "minecraft:stone", true, 10);
        memory.recordBrokenBlock(1, 20, 0, "minecraft:the_nether", "minecraft:netherrack", true, 20);

        assertEquals(1, memory.currentMiningChain);
        assertEquals(2, memory.miningSessions);
    }

    @Test
    void nonRockBreakEndsCurrentMiningChain() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(0, 20, 0, "minecraft:overworld", "minecraft:stone", true, 10);
        memory.recordBrokenBlock(1, 20, 0, "minecraft:overworld", "minecraft:dirt", false, 20);
        assertEquals(0, memory.currentMiningChain);
        memory.recordBrokenBlock(2, 20, 0, "minecraft:overworld", "minecraft:stone", true, 30);
        assertEquals(1, memory.currentMiningChain);
        assertEquals(2, memory.miningSessions);
    }

    @Test
    void historiesAndCountersRemainBounded() {
        PlayerMemory memory = new PlayerMemory();
        for (int i = 0; i < 700; i++) {
            memory.recordMovement(i * 3.0D, 64, 0, "minecraft:overworld", i * 100L);
        }
        for (int i = 0; i < 2_200; i++) {
            memory.recordBrokenBlock(i, -40, 0, "minecraft:overworld", "test:stone_" + i, true, i);
        }
        assertEquals(PlayerMemory.MAX_ROUTE_SAMPLES, memory.route.size());
        assertEquals(PlayerMemory.MAX_MINE_SAMPLES, memory.mineHistory.size());
        assertTrue(memory.minedBlockTypes.size() <= PlayerMemory.MAX_COUNTER_KEYS);
        assertTrue(memory.brokenBlockTypes.size() <= PlayerMemory.MAX_COUNTER_KEYS);
    }

    @Test
    void recordsDirtAsTunnelTerrainWithoutCountingItAsRock() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                4, 30, -2, "minecraft:overworld", "minecraft:dirt",
                false, true, 100L
        );

        assertEquals(1, memory.getTunnelCellCount());
        assertEquals(1L, memory.tunnelBlocksCleared);
        assertEquals(0L, memory.rockBlocksBroken);
        assertEquals("minecraft:dirt", memory.tunnelCells.values().iterator().next().blockId);
    }

    @Test
    void replacesPreviouslyRecordedCellAtSamePosition() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                4, 30, -2, "minecraft:overworld", "minecraft:dirt",
                false, true, 100L
        );
        memory.recordBrokenBlock(
                4, 30, -2, "minecraft:overworld", "minecraft:stone",
                true, true, 200L
        );

        assertEquals(1, memory.getTunnelCellCount());
        assertEquals(2L, memory.tunnelBlocksCleared);
        assertEquals("minecraft:stone", memory.tunnelCells.values().iterator().next().blockId);
    }

    @Test
    void placedBlockInvalidatesPreviouslyMinedTunnelCell() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                7, 18, -3, "minecraft:overworld", "minecraft:stone",
                true, true, 500L
        );

        assertTrue(memory.hasTunnelCell("minecraft:overworld", 7, 18, -3));
        assertTrue(memory.removeTunnelCell("minecraft:overworld", 7, 18, -3));
        assertFalse(memory.hasTunnelCell("minecraft:overworld", 7, 18, -3));
        assertEquals(0, memory.getTunnelCellCount());
        assertEquals(1L, memory.tunnelBlocksCleared);
    }

    @Test
    void tunnelCellInvalidationIsDimensionSpecific() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                7, 18, -3, "minecraft:overworld", "minecraft:stone",
                true, true, 500L
        );
        memory.recordBrokenBlock(
                7, 18, -3, "minecraft:the_nether", "minecraft:netherrack",
                true, true, 600L
        );

        assertTrue(memory.removeTunnelCell("minecraft:overworld", 7, 18, -3));
        assertFalse(memory.hasTunnelCell("minecraft:overworld", 7, 18, -3));
        assertTrue(memory.hasTunnelCell("minecraft:the_nether", 7, 18, -3));
        assertEquals(1, memory.getTunnelCellCount());
    }

    @Test
    void discoveredAirIsTrackedSeparatelyFromMinedCells() {
        PlayerMemory memory = new PlayerMemory();
        assertTrue(memory.recordDiscoveredAir(2, 18, -4, "minecraft:overworld", 700L));
        assertFalse(memory.recordDiscoveredAir(2, 18, -4, "minecraft:overworld", 701L));

        assertEquals(1, memory.getTunnelCellCount());
        assertEquals(0, memory.getMinedTunnelCellCount());
        assertEquals(1, memory.getObservedAirCellCount());
        assertEquals(0L, memory.tunnelBlocksCleared);
        PlayerMemory.TunnelCell cell = memory.tunnelCells.values().iterator().next();
        assertEquals("minecraft:air", cell.blockId);
        assertEquals(PlayerMemory.TUNNEL_SOURCE_OBSERVED_AIR, cell.source);
    }

    @Test
    void discoveredAirDoesNotOverwriteMinedEvidence() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                2, 18, -4, "minecraft:overworld", "minecraft:deepslate",
                true, true, 700L
        );

        assertFalse(memory.recordDiscoveredAir(2, 18, -4, "minecraft:overworld", 701L));
        assertEquals(1, memory.getMinedTunnelCellCount());
        assertEquals(0, memory.getObservedAirCellCount());
        assertEquals("minecraft:deepslate", memory.tunnelCells.values().iterator().next().blockId);
    }

    @Test
    void airDiscoveryCannotEvictMinedCellsWhenMemoryIsFull() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                0, 20, 0, "minecraft:overworld", "minecraft:stone",
                true, true, 1L
        );
        for (int i = 1; i < PlayerMemory.MAX_TUNNEL_CELLS; i++) {
            memory.recordBrokenBlock(
                    i, 20, 0, "minecraft:overworld", "minecraft:stone",
                    true, true, i + 1L
            );
        }

        assertFalse(memory.recordDiscoveredAir(10_000, 20, 0, "minecraft:overworld", 9_000L));
        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS, memory.getTunnelCellCount());
        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS, memory.getMinedTunnelCellCount());
        assertTrue(memory.hasTunnelCell("minecraft:overworld", 0, 20, 0));
    }

    @Test
    void minedEvidenceEvictsObservedAirBeforeOtherMinedCells() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordBrokenBlock(
                0, 20, 0, "minecraft:overworld", "minecraft:stone",
                true, true, 1L
        );
        for (int i = 1; i < PlayerMemory.MAX_TUNNEL_CELLS; i++) {
            assertTrue(memory.recordDiscoveredAir(i, 20, 0, "minecraft:overworld", i + 1L));
        }

        memory.recordBrokenBlock(
                10_000, 20, 0, "minecraft:overworld", "minecraft:deepslate",
                true, true, 9_000L
        );

        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS, memory.getTunnelCellCount());
        assertEquals(2, memory.getMinedTunnelCellCount());
        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS - 2, memory.getObservedAirCellCount());
        assertTrue(memory.hasTunnelCell("minecraft:overworld", 0, 20, 0));
        assertFalse(memory.hasTunnelCell("minecraft:overworld", 1, 20, 0));
    }

    @Test
    void normalizeTreatsLegacyCellsWithoutSourceAsMined() {
        PlayerMemory memory = new PlayerMemory();
        PlayerMemory.TunnelCell legacy = new PlayerMemory.TunnelCell(
                1, 20, 2, "minecraft:overworld", "minecraft:stone", 5L
        );
        legacy.source = null;
        memory.tunnelCells.put("legacy", legacy);

        memory.normalize();

        assertEquals(PlayerMemory.TUNNEL_SOURCE_MINED, memory.tunnelCells.get("legacy").source);
        assertEquals(1, memory.getMinedTunnelCellCount());
        assertEquals(0, memory.getObservedAirCellCount());
    }

    @Test
    void tunnelVoxelHistoryRemainsBounded() {
        PlayerMemory memory = new PlayerMemory();
        for (int x = 0; x < PlayerMemory.MAX_TUNNEL_CELLS + 10; x++) {
            memory.recordBrokenBlock(
                    x, 20, 0, "minecraft:overworld", "minecraft:stone",
                    true, true, x
            );
        }

        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS, memory.getTunnelCellCount());
        assertFalse(memory.tunnelCells.containsKey("minecraft:overworld|0,20,0"));
        assertTrue(memory.tunnelCells.containsKey(
                "minecraft:overworld|" + (PlayerMemory.MAX_TUNNEL_CELLS + 9) + ",20,0"
        ));
        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS + 10L, memory.tunnelBlocksCleared);
    }

    @Test
    void normalizeRestoresChronologicalVoxelEvictionOrder() {
        PlayerMemory memory = new PlayerMemory();
        memory.tunnelCells = new HashMap<>();
        for (int tick = PlayerMemory.MAX_TUNNEL_CELLS; tick >= 0; tick--) {
            String key = "cell-" + tick;
            memory.tunnelCells.put(key, new PlayerMemory.TunnelCell(
                    tick, 20, 0, "minecraft:overworld", "minecraft:stone", tick
            ));
        }

        memory.normalize();

        assertEquals(PlayerMemory.MAX_TUNNEL_CELLS, memory.getTunnelCellCount());
        assertFalse(memory.tunnelCells.containsKey("cell-0"));
        assertTrue(memory.tunnelCells.containsKey("cell-" + PlayerMemory.MAX_TUNNEL_CELLS));
        assertEquals(1L, memory.tunnelCells.values().iterator().next().gameTick);
    }

    @Test
    void normalizeTrimsHistoriesAndRepairsNullCollections() {
        PlayerMemory memory = new PlayerMemory();
        memory.route = null;
        memory.mineHistory = null;
        memory.tunnelCells = null;
        memory.brokenBlockTypes = null;
        memory.minedBlockTypes = null;
        memory.miningDirections = null;
        memory.dimensionVisits = null;
        memory.miningDepthBands = null;
        memory.normalize();

        assertNotNull(memory.route);
        assertNotNull(memory.mineHistory);
        assertNotNull(memory.tunnelCells);
        assertNotNull(memory.brokenBlockTypes);
        assertNotNull(memory.minedBlockTypes);
        assertNotNull(memory.miningDirections);
        assertNotNull(memory.dimensionVisits);
        assertNotNull(memory.miningDepthBands);
    }

    @Test
    void normalizeRepairsDecisionPlannerState() {
        PlayerMemory memory = new PlayerMemory();
        memory.operatorPhase = null;
        memory.operatorArchetype = null;
        memory.plannedOperatorEvent = null;
        memory.operatorReason = null;
        memory.operatorConfidence = Double.NaN;
        memory.lastBrainEvaluationAt = -50L;
        memory.lastOperatorDecisionAt = -20L;
        memory.operatorDecisionCount = -10L;

        memory.normalize();

        assertEquals("OBSERVING", memory.operatorPhase);
        assertEquals("UNKNOWN", memory.operatorArchetype);
        assertEquals("NONE", memory.plannedOperatorEvent);
        assertEquals("", memory.operatorReason);
        assertEquals(0.0D, memory.operatorConfidence, 0.0001D);
        assertEquals(-1L, memory.lastBrainEvaluationAt);
        assertEquals(-1L, memory.lastOperatorDecisionAt);
        assertEquals(0L, memory.operatorDecisionCount);
    }


    @Test
    void recordsAndRemovesPlayerPlacedConstructionBlocks() {
        PlayerMemory memory = new PlayerMemory();
        assertTrue(memory.recordPlacedBlock(
                10, 64, -4, "minecraft:overworld", "minecraft:oak_planks", 100L
        ));
        assertEquals(1, memory.getConstructionBlockCount());
        assertEquals(1L, memory.blocksPlaced);
        assertTrue(memory.constructionAnalysisDirty);

        assertTrue(memory.removePlacedBlock(10, 64, -4, "minecraft:overworld", 120L));
        assertEquals(0, memory.getConstructionBlockCount());
        assertEquals(1L, memory.constructionBlocksRemoved);
        assertEquals(120L, memory.lastConstructionChangeAt);
        assertTrue(memory.constructionAnalysisDirty);
    }

    @Test
    void constructionHistoryIsDimensionSpecificAndBounded() {
        PlayerMemory memory = new PlayerMemory();
        memory.recordPlacedBlock(1, 64, 1, "minecraft:overworld", "minecraft:stone", 1L);
        memory.recordPlacedBlock(1, 64, 1, "minecraft:the_nether", "minecraft:stone", 2L);

        assertTrue(memory.removePlacedBlock(1, 64, 1, "minecraft:overworld", 3L));
        assertEquals(1, memory.getConstructionBlockCount());
        assertTrue(memory.constructionBlocks.containsKey("minecraft:the_nether|1,64,1"));

        for (int i = 0; i < PlayerMemory.MAX_CONSTRUCTION_BLOCKS + 5; i++) {
            memory.recordPlacedBlock(i, 70, 0, "minecraft:overworld", "minecraft:stone", 10L + i);
        }
        assertEquals(PlayerMemory.MAX_CONSTRUCTION_BLOCKS, memory.getConstructionBlockCount());
        assertFalse(memory.constructionBlocks.containsKey("minecraft:the_nether|1,64,1"));
    }

    @Test
    void normalizeRepairsAndTrimsConstructionMemory() {
        PlayerMemory memory = new PlayerMemory();
        memory.constructionBlocks = null;
        memory.constructionComplexity = Double.NaN;
        memory.recognizedBuildingCount = -1;
        memory.normalize();
        assertNotNull(memory.constructionBlocks);
        assertEquals(0.0D, memory.constructionComplexity, 0.0001D);
        assertEquals(0, memory.recognizedBuildingCount);

        for (int i = 0; i < PlayerMemory.MAX_CONSTRUCTION_BLOCKS + 2; i++) {
            memory.recordPlacedBlock(i, 64, 0, "minecraft:overworld", "minecraft:stone", i);
        }
        memory.normalize();
        assertEquals(PlayerMemory.MAX_CONSTRUCTION_BLOCKS, memory.getConstructionBlockCount());
        assertFalse(memory.constructionBlocks.containsKey("minecraft:overworld|0,64,0"));
    }

}
