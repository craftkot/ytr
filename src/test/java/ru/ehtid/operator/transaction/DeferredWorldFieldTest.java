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
