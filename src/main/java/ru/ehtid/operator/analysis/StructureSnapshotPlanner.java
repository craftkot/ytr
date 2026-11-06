package ru.ehtid.operator.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

/**
 * Pure, non-mutating selection and translation preflight for tracked constructions.
 * A selected component contains only observed placed blocks, not hidden terrain.
 */
public final class StructureSnapshotPlanner {
    public static final int MAX_CAPTURE_BLOCKS = 4096;
    public static final int MIN_BUILDING_BLOCKS = 24;
    public static final int MAX_ANCHOR_DISTANCE = 48;
    public static final int MAX_TRANSLATION_BLOCKS = 4096;

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private StructureSnapshotPlanner() { }

    /**
     * Choose the closest observed six-neighbour building component to the player.
     * Does not trust old, aggregated shell summaries for component identity.
     */
    public static Selection select(Collection<PlayerMemory.ConstructionBlock> blocks,
                                   String dimension, int playerX, int playerY, int playerZ) {
        if (blocks == null || dimension == null) return null;
        Map<Pos, PlayerMemory.ConstructionBlock> remaining = new HashMap<>();
        for (PlayerMemory.ConstructionBlock block : blocks) {
            if (block == null || !dimension.equals(block.dimension)
                    || block.blockId == null || block.blockId.isBlank()) continue;
            remaining.put(new Pos(block.x, block.y, block.z), block);
        }
        List<Selection> candidates = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Pos seed = remaining.keySet().iterator().next();
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            queue.add(seed);
            List<PlayerMemory.ConstructionBlock> connected = new ArrayList<>();
            connected.add(remaining.remove(seed));
            int minX = seed.x, minY = seed.y, minZ = seed.z;
            int maxX = seed.x, maxY = seed.y, maxZ = seed.z;
            long minDistanceSquared = distanceSquared(seed.x, seed.y, seed.z,
                    playerX, playerY, playerZ);
            boolean oversized = false;
            while (!queue.isEmpty()) {
                Pos current = queue.removeFirst();
                for (int[] direction : NEIGHBOURS) {
                    Pos next = new Pos(current.x + direction[0],
                            current.y + direction[1], current.z + direction[2]);
                    PlayerMemory.ConstructionBlock adjacent = remaining.remove(next);
                    if (adjacent == null) continue;
                    queue.add(next);
                    if (connected.size() < MAX_CAPTURE_BLOCKS + 1) {
                        connected.add(adjacent);
                    } else {
                        oversized = true;
                    }
                    minX = Math.min(minX, next.x);
                    minY = Math.min(minY, next.y);
                    minZ = Math.min(minZ, next.z);
                    maxX = Math.max(maxX, next.x);
                    maxY = Math.max(maxY, next.y);
                    maxZ = Math.max(maxZ, next.z);
                    minDistanceSquared = Math.min(minDistanceSquared,
                            distanceSquared(next.x, next.y, next.z,
                                    playerX, playerY, playerZ));
                }
            }
            boolean isBuilding = connected.size() >= MIN_BUILDING_BLOCKS
                    && maxX - minX >= 2 && maxY - minY >= 2 && maxZ - minZ >= 2;
            if (!isBuilding || minDistanceSquared > (long) MAX_ANCHOR_DISTANCE * MAX_ANCHOR_DISTANCE)
                continue;
            if (oversized || connected.size() > MAX_CAPTURE_BLOCKS) {
                candidates.add(new Selection(dimension, minX, minY, minZ, maxX, maxY, maxZ,
                        minDistanceSquared, true, List.of()));
            } else {
                connected.sort(Comparator.comparingInt((PlayerMemory.ConstructionBlock b) -> b.x)
                        .thenComparingInt(b -> b.y).thenComparingInt(b -> b.z));
                candidates.add(new Selection(dimension, minX, minY, minZ, maxX, maxY, maxZ,
                        minDistanceSquared, false, connected));
            }
        }
        return candidates.stream()
                .min(Comparator.comparingLong(Selection::distanceSquared)
                        .thenComparingInt(Selection::minX)
                        .thenComparingInt(Selection::minY)
                        .thenComparingInt(Selection::minZ))
                .orElse(null);
    }

    public static Check translation(Selection source, int anchorX, int anchorY, int anchorZ,
                                    int minBuildY, int maxBuildYExclusive,
                                    DestinationInspector inspector) {
        if (source == null || source.oversized() || source.blocks().isEmpty())
            return new Check(false, "NO_SAFE_SOURCE", 0, 0);
        if (inspector == null) return new Check(false, "NO_DESTINATION_INSPECTOR", 0, 0);

        long dx = (long) anchorX - source.minX();
        long dy = (long) anchorY - source.minY();
        long dz = (long) anchorZ - source.minZ();
        if (dx == 0 && dy == 0 && dz == 0)
            return new Check(false, "SAME_POSITION", 0, 0);
        if (Math.abs(dx) > MAX_TRANSLATION_BLOCKS
                || Math.abs(dy) > MAX_TRANSLATION_BLOCKS
                || Math.abs(dz) > MAX_TRANSLATION_BLOCKS)
            return new Check(false, "TRANSLATION_LIMIT", 0, 0);

        if (source.minY() + dy < minBuildY || source.maxY() + dy >= maxBuildYExclusive)
            return new Check(false, "OUT_OF_BUILD_HEIGHT", 0, 0);
        if (source.minX() + dx < -30_000_000L || source.maxX() + dx >= 30_000_000L
                || source.minZ() + dz < -30_000_000L || source.maxZ() + dz >= 30_000_000L)
            return new Check(false, "OUT_OF_WORLD_BORDER", 0, 0);

        // AABB overlap is rejected even if no placed blocks overlap. Without
        // capturing air/natural terrain and block entities, a partial shift is unsafe.
        if (intersects(source.minX(), source.maxX(), source.minX() + dx, source.maxX() + dx)
                && intersects(source.minY(), source.maxY(), source.minY() + dy, source.maxY() + dy)
                && intersects(source.minZ(), source.maxZ(), source.minZ() + dz, source.maxZ() + dz))
            return new Check(false, "SOURCE_DESTINATION_OVERLAP", 0, 0);

        int checked = 0;
        for (PlayerMemory.ConstructionBlock block : source.blocks()) {
            int x = Math.toIntExact(block.x + dx);
            int y = Math.toIntExact(block.y + dy);
            int z = Math.toIntExact(block.z + dz);
            if (!inspector.loaded(x, y, z))
                return new Check(false, "UNLOADED_DESTINATION", checked, 0);
            if (!inspector.empty(x, y, z))
                return new Check(false, "DESTINATION_OCCUPIED", checked + 1, 1);
            checked++;
        }
        // Occupancy checks cannot establish safe support, collision, redstone
        // integrity, environmental dependencies or full rollback.
        return new Check(true, "PREVIEW_ONLY", checked, 0);
    }

    private static boolean intersects(long a, long b, long c, long d) {
        return a <= d && c <= b;
    }

    private static long distanceSquared(int x, int y, int z, int px, int py, int pz) {
        long dx = (long) x - px, dy = (long) y - py, dz = (long) z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public interface DestinationInspector {
        boolean loaded(int x, int y, int z);
        boolean empty(int x, int y, int z);
    }

    public record Pos(int x, int y, int z) { }

    public record Selection(String dimension, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ, long distanceSquared,
                            boolean oversized, List<PlayerMemory.ConstructionBlock> blocks) {
        public Selection {
            blocks = List.copyOf(blocks);
        }

        public int blockCount() { return blocks.size(); }
        public String bounds() {
            return minX + "," + minY + "," + minZ
                    + " -> " + maxX + "," + maxY + "," + maxZ;
        }
    }

    public record Check(boolean eligible, String reason, int checkedBlocks, int collisions) { }
}
