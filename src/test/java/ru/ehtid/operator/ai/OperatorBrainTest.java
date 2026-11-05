package ru.ehtid.operator.ai;

import org.junit.jupiter.api.Test;

import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class OperatorBrainTest {
    @Test
    void worldObservationGatePreventsPlanning() {
        PlayerMemory memory = readyPlayer();
        memory.distanceTravelled = 8_000.0D;
        for (int i = 0; i < 24; i++) {
            memory.route.add(new PlayerMemory.RouteSample(i, 64, 0, "minecraft:overworld", i * 20L));
        }
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, false, 30_000L);
        assertEquals("OBSERVING", decision.phase());
        assertEquals("NONE", decision.event());
    }

    @Test
    void personalObservationGatePreventsPlanning() {
        PlayerMemory memory = new PlayerMemory();
        memory.observedTicks = 23_999L;
        memory.distanceTravelled = 8_000.0D;
        for (int i = 0; i < 24; i++) {
            memory.route.add(new PlayerMemory.RouteSample(i, 64, 0, "minecraft:overworld", i * 20L));
        }
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 30_000L);
        assertEquals("OBSERVING", decision.phase());
        assertEquals("NONE", decision.event());
    }

    @Test
    void insufficientEvidenceKeepsBrainInCalibration() {
        OperatorBrain.Decision decision = OperatorBrain.evaluate(readyPlayer(), true, 24_000L);
        assertEquals("CALIBRATING", decision.phase());
        assertEquals("UNKNOWN", decision.archetype());
        assertEquals("NONE", decision.event());
    }

    @Test
    void explorerProfileSelectsRouteEchoPlan() {
        PlayerMemory memory = readyPlayer();
        memory.distanceTravelled = 8_000.0D;
        for (int i = 0; i < 96; i++) {
            memory.route.add(new PlayerMemory.RouteSample(i, 64, 0, "minecraft:overworld", i * 20L));
        }
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 24_000L);
        assertEquals("READY", decision.phase());
        assertEquals("EXPLORER", decision.archetype());
        assertEquals("PATH_ECHO", decision.event());
        assertTrue(decision.confidence() >= 0.30D);
    }

    @Test
    void minerProfileSelectsDepthLurePlan() {
        PlayerMemory memory = readyPlayer();
        memory.rockBlocksBroken = 300L;
        memory.miningSessions = 30L;
        memory.miningDepthBands.put("deep_minus1_minus32", 200L);
        for (int i = 0; i < 20; i++) {
            memory.mineHistory.add(new PlayerMemory.MineSample(i, -32, 0,
                    "minecraft:overworld", "minecraft:deepslate", i * 20L));
        }
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 24_000L);
        assertEquals("READY", decision.phase());
        assertEquals("MINER", decision.archetype());
        assertEquals("DEPTH_LURE", decision.event());
    }

    @Test
    void mappedCaveProfileSelectsTunnelFoldPlan() {
        PlayerMemory memory = readyPlayer();
        for (int i = 0; i < 220; i++) {
            assertTrue(memory.recordDiscoveredAir(i, 20, 0, "minecraft:overworld", i));
        }
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 24_000L);
        assertEquals("READY", decision.phase());
        assertEquals("CAVER", decision.archetype());
        assertEquals("TUNNEL_FOLD", decision.event());
    }

    @Test
    void decisionCooldownPreventsImmediatePlanSwitch() {
        PlayerMemory memory = readyPlayer();
        memory.distanceTravelled = 8_000.0D;
        for (int i = 0; i < 96; i++) {
            memory.route.add(new PlayerMemory.RouteSample(i, 64, 0, "minecraft:overworld", i * 20L));
        }
        OperatorBrain.Decision first = OperatorBrain.evaluate(memory, true, 24_000L);
        assertTrue(OperatorBrain.apply(memory, first, 24_000L));
        assertEquals(1L, memory.operatorDecisionCount);

        memory.miningDepthBands.put("high_64_plus", 50L);
        memory.miningDepthBands.put("surface_0_63", 50L);
        memory.miningDepthBands.put("deep_minus1_minus32", 150L);
        memory.miningDepthBands.put("deepslate_minus33_below", 150L);
        for (int i = 0; i < 256; i++) {
            memory.recordBrokenBlock(i, -40, 0, "minecraft:overworld",
                    "minecraft:deepslate", true, true, 25_000L + i);
        }
        OperatorBrain.Decision duringCooldown = OperatorBrain.evaluate(memory, true, 26_000L);
        assertEquals("COOLDOWN", duringCooldown.phase());
        assertEquals("PATH_ECHO", duringCooldown.event());

        OperatorBrain.Decision afterCooldown = OperatorBrain.evaluate(memory, true, 31_000L);
        assertEquals("READY", afterCooldown.phase());
        assertEquals("DEPTH_LURE", afterCooldown.event());
        assertTrue(OperatorBrain.apply(memory, afterCooldown, 31_000L));
        assertEquals(2L, memory.operatorDecisionCount);
    }


    @Test
    void builderProfileSelectsHomeEchoPlan() {
        PlayerMemory memory = readyPlayer();
        memory.blocksPlaced = 80L;
        memory.recognizedStructureCount = 2;
        memory.recognizedBuildingCount = 1;
        memory.largestStructureBlockCount = 72;
        memory.largestBuildingBlockCount = 48;
        memory.constructionComplexity = 0.65D;

        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 24_000L);

        assertEquals("READY", decision.phase());
        assertEquals("BUILDER", decision.archetype());
        assertEquals("HOME_ECHO", decision.event());
        assertTrue(decision.confidence() >= 0.30D);
    }


    @Test
    void enclosedBuildingEvidenceStrengthensHomeEchoReasoning() {
        PlayerMemory memory = readyPlayer();
        memory.blocksPlaced = 160L;
        memory.recognizedStructureCount = 1;
        memory.recognizedBuildingCount = 1;
        memory.recognizedEnclosedBuildingCount = 1;
        memory.largestStructureBlockCount = 96;
        memory.largestBuildingBlockCount = 96;
        memory.largestEnclosedVoidCells = 72;
        memory.largestFloorCoverage = 1.0D;
        memory.largestRoofCoverage = 0.9D;
        memory.largestWallCoverage = 0.95D;
        memory.constructionComplexity = 0.8D;

        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, 24_000L);

        assertEquals("READY", decision.phase());
        assertEquals("BUILDER", decision.archetype());
        assertEquals("HOME_ECHO", decision.event());
        assertTrue(decision.reason().contains("enclosed geometric void"));
    }

    private static PlayerMemory readyPlayer() {
        PlayerMemory memory = new PlayerMemory();
        memory.observedTicks = OperatorBrain.REQUIRED_PERSONAL_TICKS;
        return memory;
    }
}
