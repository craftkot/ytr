package ru.ehtid.operator.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class OperatorWorldDataTest {
    @Test
    void worldObservationElapsedNeverGoesNegative() {
        OperatorWorldData data = new OperatorWorldData();
        data.observationStartedAt = 500L;
        assertEquals(0L, data.worldObservationElapsed(400L));
        assertEquals(250L, data.worldObservationElapsed(750L));
    }

    @Test
    void normalizeRepairsMissingCollectionsAndMigratesSchema() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 2;
        data.players = null;
        data.normalize();
        assertEquals(10, data.schemaVersion);
        assertNotNull(data.players);
    }

    @Test
    void schemaThreeSavePreservesMinedCellsAndMigratesTheirSource() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 3;
        PlayerMemory memory = new PlayerMemory();
        PlayerMemory.TunnelCell legacy = new PlayerMemory.TunnelCell(
                12, 24, -8, "minecraft:overworld", "minecraft:stone", 123L
        );
        legacy.source = null;
        memory.tunnelCells.put("minecraft:overworld|12,24,-8", legacy);
        data.players.put("player-uuid", memory);

        data.normalize();

        assertEquals(10, data.schemaVersion);
        assertEquals(1, memory.getTunnelCellCount());
        assertEquals(1, memory.getMinedTunnelCellCount());
        assertEquals(0, memory.getObservedAirCellCount());
        assertEquals(PlayerMemory.TUNNEL_SOURCE_MINED,
                memory.tunnelCells.values().iterator().next().source);
    }

    @Test
    void normalizeRepairsLegacyPlayerCollections() {
        OperatorWorldData data = new OperatorWorldData();
        PlayerMemory memory = new PlayerMemory();
        memory.minedBlockTypes = null;
        memory.dimensionVisits = null;
        memory.miningDepthBands = null;
        data.players = new LinkedHashMap<>();
        data.players.put("player", memory);
        data.normalize();
        assertNotNull(memory.minedBlockTypes);
        assertNotNull(memory.dimensionVisits);
        assertNotNull(memory.miningDepthBands);
    }

    @Test
    void schemaFourSaveMigratesDecisionFieldsWithoutDiscardingObservationData() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 4;
        PlayerMemory memory = new PlayerMemory();
        memory.uuid = "player-uuid";
        memory.observedTicks = 24_000L;
        memory.distanceTravelled = 1234.5D;
        memory.plannedOperatorEvent = null;
        memory.operatorPhase = null;
        data.players.put("player-uuid", memory);

        data.normalize();

        assertEquals(10, data.schemaVersion);
        assertEquals(24_000L, memory.observedTicks);
        assertEquals(1234.5D, memory.distanceTravelled);
        assertEquals("NONE", memory.plannedOperatorEvent);
        assertEquals("OBSERVING", memory.operatorPhase);
        assertEquals("UNKNOWN", memory.operatorArchetype);
        assertEquals(-1L, memory.lastBrainEvaluationAt);
        assertEquals(-1L, memory.lastOperatorDecisionAt);
    }


    @Test
    void schemaFiveSaveMigratesWithoutDroppingPlannerOrConstructionData() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 5;
        PlayerMemory memory = new PlayerMemory();
        memory.uuid = "player-uuid";
        memory.observedTicks = 25_000L;
        memory.operatorDecisionCount = 3L;
        memory.constructionBlocks.put("minecraft:overworld|1,64,2",
                new PlayerMemory.ConstructionBlock(
                        1, 64, 2, "minecraft:overworld", "minecraft:oak_planks", 500L
                ));
        memory.blocksPlaced = 1L;
        data.players.put("player-uuid", memory);

        data.normalize();

        assertEquals(10, data.schemaVersion);
        assertEquals(25_000L, memory.observedTicks);
        assertEquals(3L, memory.operatorDecisionCount);
        assertEquals(1, memory.getConstructionBlockCount());
        assertEquals("minecraft:oak_planks",
                memory.constructionBlocks.values().iterator().next().blockId);
        assertEquals(1L, memory.blocksPlaced);
    }


    @Test
    void schemaSixMigrationPreservesConstructionMapAndSchedulesSemanticReanalysis() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 6;
        PlayerMemory memory = new PlayerMemory();
        memory.uuid = "player-uuid";
        memory.blocksPlaced = 30L;
        memory.constructionAnalysisDirty = false;
        memory.lastConstructionAnalysisAt = 900L;
        memory.constructionBlocks.put("minecraft:overworld|1,64,2",
                new PlayerMemory.ConstructionBlock(
                        1, 64, 2, "minecraft:overworld", "minecraft:oak_stairs", 500L
                ));
        data.players.put("player-uuid", memory);

        data.normalize();

        assertEquals(10, data.schemaVersion);
        assertEquals(30L, memory.blocksPlaced);
        assertEquals(1, memory.getConstructionBlockCount());
        PlayerMemory.ConstructionBlock migrated =
                memory.constructionBlocks.get("minecraft:overworld|1,64,2");
        assertNotNull(migrated);
        assertEquals("", migrated.blockState);
        assertTrue(memory.constructionAnalysisDirty);
        assertEquals(-1L, memory.lastConstructionAnalysisAt);
        assertEquals(0, memory.recognizedEnclosedBuildingCount);
    }


    @Test
    void schemaSevenRetainsBlockStatesAndRecalculatesRoomMetrics() {
        OperatorWorldData data = new OperatorWorldData();
        data.schemaVersion = 7;
        PlayerMemory memory = new PlayerMemory();
        memory.uuid = "room-player";
        memory.observedTicks = 25_000L;
        memory.lastConstructionAnalysisAt = 500L;
        memory.constructionAnalysisDirty = false;
        memory.constructionBlocks.put("minecraft:overworld|10,70,12",
                new PlayerMemory.ConstructionBlock(
                        10, 70, 12, "minecraft:overworld", "minecraft:oak_stairs",
                        "Block{minecraft:oak_stairs}[facing=north,half=bottom]", 200L));
        data.players.put(memory.uuid, memory);

        data.normalize();

        assertEquals(10, data.schemaVersion);
        assertEquals(25_000L, memory.observedTicks);
        assertEquals(1, memory.getConstructionBlockCount());
        assertTrue(memory.constructionBlocks.values().iterator().next().blockState
                .contains("facing=north"));
        assertTrue(memory.constructionAnalysisDirty);
        assertEquals(-1L, memory.lastConstructionAnalysisAt);
        assertEquals(0, memory.recognizedRoomCount);
    }


    @Test
    void autonomousAnomaliesAreDisabledOnNewWorldsAndLegacyMigrations() {
        OperatorWorldData fresh = new OperatorWorldData();
        assertEquals(10, fresh.schemaVersion);
        assertFalse(fresh.autonomousAnomaliesEnabled);
        OperatorWorldData legacy = new OperatorWorldData();
        legacy.schemaVersion = 8;
        legacy.autonomousAnomaliesEnabled = false;
        legacy.normalize();
        assertEquals(10, legacy.schemaVersion);
        assertFalse(legacy.autonomousAnomaliesEnabled);
        assertFalse(fresh.worldFieldEnabled);
        assertNotNull(fresh.worldFieldHistory);
        assertTrue(fresh.worldFieldHistory.isEmpty());
        legacy.worldFieldHistory = null;
        legacy.normalize();
        assertNotNull(legacy.worldFieldHistory);
        assertFalse(legacy.worldFieldEnabled);
    }

}
