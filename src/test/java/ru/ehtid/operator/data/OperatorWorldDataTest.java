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
        assertEquals(3, data.schemaVersion);
        assertNotNull(data.players);
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
}
