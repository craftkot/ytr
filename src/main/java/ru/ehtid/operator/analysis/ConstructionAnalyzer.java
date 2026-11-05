package ru.ehtid.operator.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

/**
 * Builds a bounded, event-derived map of player-placed blocks into connected
 * components. A "building candidate" is a geometric heuristic, not proof that
 * a component is a home or that it contains a usable interior.
 */
public final class ConstructionAnalyzer {
    public static final int MIN_STRUCTURE_BLOCKS = 8;
    public static final int MIN_BUILDING_BLOCKS = 24;

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private ConstructionAnalyzer() { }

    public static Analysis analyze(Collection<PlayerMemory.ConstructionBlock> source) {
        return analyze(source, null);
    }

    /** When dimension is null, components from every dimension are analysed independently. */
    public static Analysis analyze(Collection<PlayerMemory.ConstructionBlock> source, String dimension) {
        Map<CellKey, PlayerMemory.ConstructionBlock> remaining = new LinkedHashMap<>();
        if (source != null) {
            for (PlayerMemory.ConstructionBlock block : source) {
                if (block == null || block.dimension == null || block.blockId == null
                        || block.blockId.isBlank()) continue;
                if (dimension != null && !dimension.equals(block.dimension)) continue;
                CellKey key = new CellKey(block.dimension, block.x, block.y, block.z);
                remaining.put(key, block);
            }
        }

        int trackedBlocks = remaining.size();
        List<Structure> structures = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Map.Entry<CellKey, PlayerMemory.ConstructionBlock> first =
                    remaining.entrySet().iterator().next();
            CellKey seed = first.getKey();
            PlayerMemory.ConstructionBlock seedBlock = remaining.remove(seed);
            Component component = new Component(seed.dimension());
            ArrayDeque<CellKey> frontier = new ArrayDeque<>();
            frontier.addLast(seed);
            component.add(seedBlock);

            while (!frontier.isEmpty()) {
                CellKey current = frontier.removeFirst();
                for (int[] direction : DIRECTIONS) {
                    CellKey neighbour = new CellKey(
                            current.dimension(),
                            current.x() + direction[0],
                            current.y() + direction[1],
                            current.z() + direction[2]
                    );
                    PlayerMemory.ConstructionBlock adjacent = remaining.remove(neighbour);
                    if (adjacent != null) {
                        frontier.addLast(neighbour);
                        component.add(adjacent);
                    }
                }
            }

            if (component.blockCount >= MIN_STRUCTURE_BLOCKS) {
                structures.add(component.toStructure());
            }
        }

        structures.sort(Comparator.comparingInt(Structure::blockCount).reversed()
                .thenComparing(Structure::dimension)
                .thenComparingInt(Structure::minX)
                .thenComparingInt(Structure::minY)
                .thenComparingInt(Structure::minZ));

        int buildings = 0;
        int largestStructure = 0;
        int largestBuilding = 0;
        double complexityTotal = 0.0D;
        for (Structure structure : structures) {
            largestStructure = Math.max(largestStructure, structure.blockCount());
            if (structure.buildingCandidate()) {
                buildings++;
                largestBuilding = Math.max(largestBuilding, structure.blockCount());
            }
            complexityTotal += 0.5D * ratio(structure.uniqueBlockTypes(), 8.0D)
                    + 0.5D * ratio(structure.blockCount(), 128.0D);
        }
        double complexity = structures.isEmpty() ? 0.0D : complexityTotal / structures.size();

        return new Analysis(
                trackedBlocks, structures.size(), buildings,
                largestStructure, largestBuilding, complexity,
                List.copyOf(structures)
        );
    }

    private static double ratio(double value, double ceiling) {
        if (value <= 0.0D || ceiling <= 0.0D) return 0.0D;
        return Math.min(1.0D, value / ceiling);
    }

    public record Analysis(
            int trackedBlockCount,
            int structureCount,
            int buildingCandidateCount,
            int largestStructureBlockCount,
            int largestBuildingBlockCount,
            double complexity,
            List<Structure> structures
    ) {
        public Analysis {
            structures = structures == null ? List.of() : List.copyOf(structures);
        }
    }

    public record Structure(
            String dimension,
            int blockCount,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int uniqueBlockTypes,
            long firstPlacedAt,
            long lastPlacedAt,
            boolean buildingCandidate,
            double confidence
    ) {
        public int spanX() { return maxX - minX + 1; }
        public int spanY() { return maxY - minY + 1; }
        public int spanZ() { return maxZ - minZ + 1; }

        public String kind() {
            return buildingCandidate ? "BUILDING_CANDIDATE" : "STRUCTURE";
        }

        public String bounds() {
            return spanX() + "x" + spanY() + "x" + spanZ()
                    + " [" + minX + "," + minY + "," + minZ
                    + " -> " + maxX + "," + maxY + "," + maxZ + "]";
        }
    }

    private record CellKey(String dimension, int x, int y, int z) { }

    private static final class Component {
        private final String dimension;
        private final Set<String> blockTypes = new HashSet<>();
        private int blockCount;
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;
        private long firstPlacedAt = Long.MAX_VALUE;
        private long lastPlacedAt = Long.MIN_VALUE;

        private Component(String dimension) {
            this.dimension = dimension;
        }

        private void add(PlayerMemory.ConstructionBlock block) {
            blockCount++;
            minX = Math.min(minX, block.x);
            minY = Math.min(minY, block.y);
            minZ = Math.min(minZ, block.z);
            maxX = Math.max(maxX, block.x);
            maxY = Math.max(maxY, block.y);
            maxZ = Math.max(maxZ, block.z);
            blockTypes.add(block.blockId);
            firstPlacedAt = Math.min(firstPlacedAt, block.gameTick);
            lastPlacedAt = Math.max(lastPlacedAt, block.gameTick);
        }

        private Structure toStructure() {
            int spanX = maxX - minX + 1;
            int spanY = maxY - minY + 1;
            int spanZ = maxZ - minZ + 1;
            boolean buildingCandidate = blockCount >= MIN_BUILDING_BLOCKS
                    && spanX >= 3 && spanY >= 3 && spanZ >= 3;
            int smallestSpan = Math.min(spanX, Math.min(spanY, spanZ));
            double confidence = buildingCandidate
                    ? Math.min(0.95D, 0.35D
                            + 0.45D * ratio(blockCount, 64.0D)
                            + 0.20D * ratio(smallestSpan, 8.0D))
                    : Math.min(0.60D, 0.15D + 0.45D * ratio(blockCount, 64.0D));
            return new Structure(
                    dimension, blockCount,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    blockTypes.size(), firstPlacedAt, lastPlacedAt,
                    buildingCandidate, confidence
            );
        }
    }
}
