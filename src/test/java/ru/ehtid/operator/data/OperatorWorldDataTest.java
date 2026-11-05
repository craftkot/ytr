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
        assertEquals(5, data.schemaVersion);
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

        assertEquals(5, data.schemaVersion);
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

        assertEquals(5, data.schemaVersion);
        assertEquals(24_000L, memory.observedTicks);
        assertEquals(1234.5D, memory.distanceTravelled);
        assertEquals("NONE", memory.plannedOperatorEvent);
        assertEquals("OBSERVING", memory.operatorPhase);
        assertEquals("UNKNOWN", memory.operatorArchetype);
        assertEquals(-1L, memory.lastBrainEvaluationAt);
        assertEquals(-1L, memory.lastOperatorDecisionAt);
    }

}
