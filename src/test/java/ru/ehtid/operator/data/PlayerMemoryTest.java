package ru.ehtid.operator.data;

import org.junit.jupiter.api.Test;

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
        assertEquals(2, memory.route.size());
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
    void normalizeTrimsHistoriesAndRepairsNullCollections() {
        PlayerMemory memory = new PlayerMemory();
        memory.route = null;
        memory.mineHistory = null;
        memory.brokenBlockTypes = null;
        memory.minedBlockTypes = null;
        memory.miningDirections = null;
        memory.dimensionVisits = null;
        memory.miningDepthBands = null;
        memory.normalize();

        assertNotNull(memory.route);
        assertNotNull(memory.mineHistory);
        assertNotNull(memory.brokenBlockTypes);
        assertNotNull(memory.minedBlockTypes);
        assertNotNull(memory.miningDirections);
        assertNotNull(memory.dimensionVisits);
        assertNotNull(memory.miningDepthBands);
    }
}
