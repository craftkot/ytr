package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.storage.RelocationWatchdogStorage;

/**
 * The intent is chosen BEFORE terrain is loaded and persisted by its caller.
 * Loading a chunk is merely a readiness signal: nothing is written in a
 * Fabric CHUNK_LOAD callback (which is not a pre-client-packet guarantee).
 *
 * An actual mutation requires both edge chunks already loaded and an
 * independent, fresh, complete field preimage/observer/hazard/WAL audit.
 */
public final class DeferredWorldFieldService {
    public static final long PLAN_SPACING_TICKS = 1200L;
    private static final int MAX_HEIGHT_SAMPLES = 64;

    private DeferredWorldFieldService() { }

    public static DeferredChunkPlan stage(MinecraftServer server, ServerPlayer actor,
                                          OperatorWorldData data, long now) {
        if (actor == null || data == null || !data.worldFieldEnabled
                || !data.autonomousAnomaliesEnabled || actor.isSpectator()
                || actor.isCreative() || data.worldFieldPending == null
                || data.worldFieldHistory == null
                || data.worldFieldPending.size() >= DeferredChunkPlan.MAX_PENDING
                || now < data.lastDeferredPlanTick
                || now - data.lastDeferredPlanTick < PLAN_SPACING_TICKS
                || data.worldFieldHistory.size() >= WorldFieldPolicy.MAX_SECTORS)
            return null;
        for (DeferredChunkPlan previous : data.worldFieldPending.values()) {
            if (previous == null || !previous.valid()) return null;
            if (previous.ownerUuid.equals(actor.getUUID().toString())) return null;
        }
        ServerLevel level = (ServerLevel) actor.level();
        String dimension = level.dimension().toString();
        int baseX = actor.blockPosition().getX();
        int baseZ = actor.blockPosition().getZ();
        int y = actor.blockPosition().getY() + 2;
        for (int radius : new int[]{192, 224, 256}) {
            for (int[] direction : new int[][]{
                    {0, -1}, {1, 0}, {-1, 0}, {0, 1}
            }) {
                int rawX = baseX + radius * direction[0];
                int x = Math.floorDiv(rawX, 16) * 16 + 15;
                int z = baseZ + radius * direction[1];
                String sector = WorldFieldPolicy.sector(dimension, x, z);
                BlockPos target = new BlockPos(x, y, z);
                if (data.worldFieldHistory.containsKey(sector)
                        || data.worldFieldPending.containsKey(sector)
                        || level.hasChunkAt(target)
                        || level.hasChunkAt(target.east())
                        || !WorldFieldService.nobodyNear(server, level, target))
                    continue;
                return DeferredChunkPlan.create(
                        actor.getUUID(), dimension, x, y, z, now);
            }
        }
        return null;
    }

    /**
     * This is NOT called inside the chunk load callback. It runs on normal
     * server ticks after natural chunk availability, and never forces loads.
     */
    public static WorldFieldService.Candidate whenReady(MinecraftServer server,
            ServerPlayer actor, OperatorWorldData data, DeferredChunkPlan plan,
            long now) throws IOException {
        if (server == null || actor == null || data == null || plan == null
                || !plan.valid() || plan.expired(now)
                || !data.worldFieldEnabled || !data.autonomousAnomaliesEnabled
                || !actor.getUUID().toString().equals(plan.ownerUuid)
                || !actor.level().dimension().toString().equals(plan.dimension)
                || actor.isCreative() || actor.isSpectator()
                || data.worldFieldHistory == null
                || data.worldFieldPending == null
                || data.worldFieldHistory.containsKey(plan.sector)
                || !data.worldFieldPending.containsKey(plan.sector)
                || data.worldFieldHistory.size() >= WorldFieldPolicy.MAX_SECTORS
                || now - data.lastWorldFieldTick < WorldFieldPolicy.COOLDOWN_TICKS)
            return null;
        ServerLevel level = (ServerLevel) actor.level();
        BlockPos target = new BlockPos(plan.targetX, plan.preferredY, plan.targetZ);
        // BOTH chunks must already exist in ServerLevel before any block access.
        if (!level.hasChunkAt(target) || !level.hasChunkAt(target.east())
                || !WorldFieldService.nobodyNear(server, level, target)
                || RelocationWatchdogStorage.scan(server).blocksNewWrites())
            return null;
        if (!WorldFieldService.historyMatchesDisk(server, data.worldFieldHistory))
            return null;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                plan.targetX, plan.targetZ);
        int bottom = Math.max(level.getMinY() + 5, surface - 80);
        for (int y = surface - 8, sampled = 0;
             y >= bottom && sampled++ < MAX_HEIGHT_SAMPLES; y--) {
            WorldFieldService.Candidate candidate = WorldFieldService.prepareAt(
                    server, actor, new BlockPos(plan.targetX,y,plan.targetZ),
                    data.worldFieldHistory);
            if (candidate == null || !plan.sector.equals(candidate.sector())
                    || !ChunkBoundaryPolicy.spansExactlyTwoChunks(candidate.journal())
                    || !plan.ownerUuid.equals(candidate.journal().ownerUuid)
                    || WorldFieldService.nearTrackedConstruction(data,candidate.journal()))
                continue;
            return candidate;
        }
        return null;
    }

    public static boolean pendingValid(Map<String, DeferredChunkPlan> pending,
                                       Map<String, String> history, long now) {
        if (pending == null || history == null
                || pending.size() > DeferredChunkPlan.MAX_PENDING) return false;
        for (var entry : pending.entrySet()) {
            DeferredChunkPlan plan = entry.getValue();
            if (plan == null || !plan.valid() || !entry.getKey().equals(plan.sector)
                    || history.containsKey(plan.sector) || plan.expired(now))
                return false;
        }
        return true;
    }
}
