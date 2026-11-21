package ru.ehtid.operator.transaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeferredWorldFieldTest {
    private static final UUID OWNER =
            UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001");

    @Test void plansOnlyStagedAtCrossChunkXEdgeAndNeverActAsPreimage() {
        var plan = DeferredChunkPlan.create(
                OWNER, "minecraft:overworld", 15, 202, 0, 24000L);
        assertTrue(plan.valid());
        assertEquals(0, plan.targetChunkX());
        assertEquals(1, plan.sourceChunkX());
        assertEquals(0, plan.chunkZ());
        assertEquals("minecraft:overworld|0,0", plan.sector);
        assertFalse(plan.expired(24000L + DeferredChunkPlan.MAX_AGE_TICKS));
        assertTrue(plan.expired(24001L + DeferredChunkPlan.MAX_AGE_TICKS));
        assertTrue(plan.expired(23999L));
        assertEquals(36, plan.planId.length());
        assertNotEquals(OWNER.toString(), plan.planId);
    }

    @Test void negativeCoordinatesUseFloorDivisionAndFullCoordinateChecks() {
        var plan = DeferredChunkPlan.create(
                OWNER, "minecraft:overworld", -1, 60, -16, 1200L);
        assertEquals(-1, plan.targetChunkX());
        assertEquals(0, plan.sourceChunkX());
        assertEquals(-1, plan.chunkZ());
        assertEquals("minecraft:overworld|-1,-1", plan.sector);
        plan.targetX = -2;
        assertFalse(plan.valid(), "An arbitrary non-edge target could bypass the border gate");
        plan.targetX = -1;
        plan.sector = "minecraft:the_nether|0,0";
        assertFalse(plan.valid(), "Serialized sector must match the coordinates and dimension");
    }

    @Test void boundedPendingPlansRejectCorruptionAndReplayedSector() {
        Map<String, DeferredChunkPlan> pending = new LinkedHashMap<>();
        Map<String, String> history = new LinkedHashMap<>();
        var plan = DeferredChunkPlan.create(
                OWNER, "minecraft:overworld", 15, 200, 0, 24000L);
        pending.put(plan.sector, plan);
        assertTrue(DeferredWorldFieldService.pendingValid(pending, history, 24001L));
        history.put(plan.sector, OWNER + "/saved");
        assertFalse(DeferredWorldFieldService.pendingValid(pending, history, 24001L));
        history.clear();
        plan.planId = "not-a-uuid";
        assertFalse(DeferredWorldFieldService.pendingValid(pending, history, 24001L));
        assertFalse(DeferredWorldFieldService.pendingValid(null, history, 24001L));
    }

    @Test void multiplayerDeferredProbeHasSingleFairOwnerPerCycle() {
        UUID secondOwner = UUID.fromString("e8d1d74a-e4b8-4200-9dc6-d6a40bc60f33");
        var first = DeferredChunkPlan.create(
                OWNER, "minecraft:overworld", 15, 202, 0, 24000L);
        var second = DeferredChunkPlan.create(
                secondOwner, "minecraft:overworld", 79, 202, 0, 24000L);
        Map<String, DeferredChunkPlan> pending = new LinkedHashMap<>();
        pending.put(first.sector, first);
        pending.put(second.sector, second);

        for (long tick = 24000L; tick <= 25600L; tick += 200L) {
            boolean a = DeferredWorldFieldService.assignedProbe(pending, OWNER, tick);
            boolean b = DeferredWorldFieldService.assignedProbe(pending, secondOwner, tick);
            assertNotEquals(a, b, "Exactly one owner gets the costly audit at tick " + tick);
            assertFalse(DeferredWorldFieldService.assignedProbe(pending, OWNER, tick + 1L));
            if (tick >= 24200L) {
                boolean previousA = DeferredWorldFieldService.assignedProbe(
                        pending, OWNER, tick - 200L);
                assertNotEquals(previousA, a, "Round-robin must not starve either player");
            }
        }
        assertFalse(DeferredWorldFieldService.assignedProbe(pending, UUID.randomUUID(), 24000L));
        assertFalse(DeferredWorldFieldService.assignedProbe(Map.of(), OWNER, 24000L));

        // Disconnecting the other owner does not block the remaining one.
        pending.remove(second.sector);
        assertTrue(DeferredWorldFieldService.assignedProbe(pending, OWNER, 24200L));

        // A corrupt stored plan fails closed instead of getting a scheduling slot.
        second.planId = "malformed";
        pending.put(second.sector, second);
        assertFalse(DeferredWorldFieldService.assignedProbe(pending, OWNER, 24200L));
        assertFalse(DeferredWorldFieldService.assignedProbe(pending, secondOwner, 24200L));
    }

    @Test void fourWriteJournalSpansExactlyTwoChunksAndRollbackWorks() {
        var border = TunnelFoldService.twoCellJournal(OWNER,
                "minecraft:overworld", new BlockPos(16,202,0),
                new BlockPos(15,202,0), 24000L);
        assertTrue(SandboxTransactionEngine.isTunnelFold(border));
        assertTrue(ChunkBoundaryPolicy.crossesChunkBoundary(border));
        assertTrue(ChunkBoundaryPolicy.spansExactlyTwoChunks(border));
        assertEquals(2, border.source.volume());
        assertEquals(2, border.target.volume());

        var intra = TunnelFoldService.twoCellJournal(OWNER,
                "minecraft:overworld", new BlockPos(1,202,0),
                new BlockPos(0,202,0), 24000L);
        assertTrue(SandboxTransactionEngine.isTunnelFold(intra));
        assertFalse(ChunkBoundaryPolicy.crossesChunkBoundary(intra));

        var negative = TunnelFoldService.twoCellJournal(OWNER,
                "minecraft:overworld", new BlockPos(0,202,-2),
                new BlockPos(-1,202,-2), 24000L);
        assertTrue(ChunkBoundaryPolicy.spansExactlyTwoChunks(negative));

        var acrossZ = TunnelFoldService.twoCellJournal(OWNER,
                "minecraft:overworld", new BlockPos(0,202,16),
                new BlockPos(0,202,15), 24000L);
        assertTrue(ChunkBoundaryPolicy.spansExactlyTwoChunks(acrossZ));
    }
}
