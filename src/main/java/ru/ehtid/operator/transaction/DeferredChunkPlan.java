package ru.ehtid.operator.transaction;

import java.util.UUID;

/**
 * A persisted INTENT to inspect a future chunk. No block preimages exist yet;
 * an intent NEVER authorizes block writes. All chunk, visibility, protection,
 * WAL and current block-state checks happen after the terrain is loaded.
 */
public final class DeferredChunkPlan {
    public static final int MAX_PENDING = 8;
    public static final long MAX_AGE_TICKS = 168_000L; // 7 Minecraft days
    public String planId = "";
    public String ownerUuid = "";
    public String dimension = "";
    public String sector = "";
    public int targetX;
    public int targetZ;
    public int preferredY;
    public long queuedAt;
    public long lastAttemptAt;

    public DeferredChunkPlan() { }

    public static DeferredChunkPlan create(UUID owner, String dimension,
                                           int targetX, int preferredY,
                                           int targetZ, long tick) {
        DeferredChunkPlan plan = new DeferredChunkPlan();
        plan.planId = UUID.randomUUID().toString();
        plan.ownerUuid = owner.toString();
        plan.dimension = dimension;
        plan.sector = WorldFieldPolicy.sector(dimension, targetX, targetZ);
        plan.targetX = targetX;
        plan.targetZ = targetZ;
        plan.preferredY = preferredY;
        plan.queuedAt = tick;
        plan.lastAttemptAt = -1L;
        if (!plan.valid()) throw new IllegalArgumentException("Invalid deferred intent");
        return plan;
    }

    public boolean valid() {
        if (!isUuid(planId) || !isUuid(ownerUuid)
                || dimension == null || dimension.isBlank() || dimension.length() > 128
                || !dimension.contains(":") || sector == null
                || preferredY < -2048 || preferredY > 2048
                || queuedAt < 0L || lastAttemptAt < -1L
                || lastAttemptAt > queuedAt + MAX_AGE_TICKS
                || targetX <= -29_999_984 || targetX >= 29_999_983
                || targetZ <= -29_999_984 || targetZ >= 29_999_983)
            return false;
        return Math.floorMod(targetX, 16) == 15
                && sector.equals(WorldFieldPolicy.sector(dimension, targetX, targetZ));
    }

    public boolean expired(long tick) {
        return tick < queuedAt || tick - queuedAt > MAX_AGE_TICKS;
    }

    public int targetChunkX() { return Math.floorDiv(targetX, 16); }

    public int sourceChunkX() { return Math.floorDiv(targetX + 1, 16); }

    public int chunkZ() { return Math.floorDiv(targetZ, 16); }

    private static boolean isUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException malformed) { return false; }
    }
}
