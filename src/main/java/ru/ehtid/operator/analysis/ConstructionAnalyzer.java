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
 * Converts confirmed player-placed blocks into connected structures and a bounded
 * geometric shell analysis. Results are estimates from placed-block evidence only:
 * they are not a scan of every block in the Minecraft world.
 */
public final class ConstructionAnalyzer {
    public static final int MIN_STRUCTURE_BLOCKS = 8;
    public static final int MIN_BUILDING_BLOCKS = 24;
    public static final long MAX_INTERIOR_SCAN_CELLS = 32_768L;
    public static final long MAX_TOTAL_INTERIOR_SCAN_CELLS_PER_ANALYSIS = 65_536L;

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
        long interiorBudget = MAX_TOTAL_INTERIOR_SCAN_CELLS_PER_ANALYSIS;
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
                Structure structure = component.toStructure(interiorBudget);
                structures.add(structure);
                if (structure.interiorScanComplete()) {
                    interiorBudget = Math.max(0L, interiorBudget
                            - (long) structure.spanX() * structure.spanY() * structure.spanZ());
                }
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
            double shellQuality = (structure.floorCoverage()
                    + structure.roofCoverage() + structure.wallCoverage()) / 3.0D;
            complexityTotal += 0.35D * ratio(structure.uniqueBlockTypes(), 8.0D)
                    + 0.35D * ratio(structure.blockCount(), 128.0D)
                    + 0.20D * shellQuality
                    + 0.10D * ratio(structure.enclosedVoidCells(), 64.0D);
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

    private static String blockPath(String blockId) {
        int separator = blockId.indexOf(':');
        return separator >= 0 ? blockId.substring(separator + 1) : blockId;
    }

    private static boolean isDoor(String blockId) {
        String path = blockPath(blockId);
        return path.endsWith("_door");
    }

    private static boolean isWindow(String blockId) {
        String path = blockPath(blockId);
        return path.equals("glass") || path.equals("glass_pane")
                || path.equals("tinted_glass")
                || path.endsWith("_glass") || path.endsWith("_glass_pane");
    }

    private static boolean isStair(String blockId) {
        return blockPath(blockId).endsWith("_stairs");
    }

    private static boolean isSlab(String blockId) {
        return blockPath(blockId).endsWith("_slab");
    }

    private static boolean isUpperDoorHalf(String blockState) {
        return blockState != null && blockState.contains("half=upper");
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
            int uniqueBlockStates,
            long firstPlacedAt,
            long lastPlacedAt,
            boolean buildingCandidate,
            double confidence,
            double floorCoverage,
            double roofCoverage,
            double wallCoverage,
            int floorLayerCount,
            int doorCount,
            int windowBlockCount,
            int stairBlockCount,
            int slabBlockCount,
            boolean interiorScanComplete,
            int unoccupiedCells,
            int enclosedVoidCells,
            String semanticKind,
            List<RoomAnalyzer.Room> rooms,
            List<RoomAnalyzer.DoorLink> doorLinks
    ) {
        public Structure {
            rooms = List.copyOf(rooms);
            doorLinks = List.copyOf(doorLinks);
        }

        public int roomCount() { return rooms.size(); }
        public int doorLinkCount() { return doorLinks.size(); }
        public int furnishedRoomCount() {
            return (int) rooms.stream().filter(room -> !"UNCLASSIFIED".equals(room.role())).count();
        }

        public int spanX() { return maxX - minX + 1; }
        public int spanY() { return maxY - minY + 1; }
        public int spanZ() { return maxZ - minZ + 1; }

        public boolean enclosedBuildingCandidate() {
            return "ENCLOSED_BUILDING".equals(semanticKind);
        }

        public String kind() {
            return semanticKind;
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
        private final Set<String> blockStates = new HashSet<>();
        private final List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        private int blockCount;
        private int doorCount;
        private int windowBlockCount;
        private int stairBlockCount;
        private int slabBlockCount;
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
            blocks.add(block);
            minX = Math.min(minX, block.x);
            minY = Math.min(minY, block.y);
            minZ = Math.min(minZ, block.z);
            maxX = Math.max(maxX, block.x);
            maxY = Math.max(maxY, block.y);
            maxZ = Math.max(maxZ, block.z);
            blockTypes.add(block.blockId);
            if (block.blockState != null && !block.blockState.isBlank()) {
                blockStates.add(block.blockState);
            }
            if (isDoor(block.blockId) && !isUpperDoorHalf(block.blockState)) doorCount++;
            if (isWindow(block.blockId)) windowBlockCount++;
            if (isStair(block.blockId)) stairBlockCount++;
            if (isSlab(block.blockId)) slabBlockCount++;
            firstPlacedAt = Math.min(firstPlacedAt, block.gameTick);
            lastPlacedAt = Math.max(lastPlacedAt, block.gameTick);
        }

        private Structure toStructure(long interiorBudget) {
            int spanX = maxX - minX + 1;
            int spanY = maxY - minY + 1;
            int spanZ = maxZ - minZ + 1;
            boolean buildingCandidate = blockCount >= MIN_BUILDING_BLOCKS
                    && spanX >= 3 && spanY >= 3 && spanZ >= 3;

            long floorArea = (long) spanX * spanZ;
            long wallArea = 2L * spanY * spanZ + 2L * spanY * spanX;
            int floorBlocks = 0;
            int roofBlocks = 0;
            long occupiedWallFaceCells = 0L;
            int floorLayers = 0;

            for (PlayerMemory.ConstructionBlock block : blocks) {
                if (block.y == minY) floorBlocks++;
                if (block.y == maxY) roofBlocks++;
                if (block.x == minX) occupiedWallFaceCells++;
                if (block.x == maxX) occupiedWallFaceCells++;
                if (block.z == minZ) occupiedWallFaceCells++;
                if (block.z == maxZ) occupiedWallFaceCells++;
            }
            long interiorFloorArea = (long) Math.max(0, spanX - 2) * Math.max(0, spanZ - 2);
            Map<Integer, Integer> interiorBlocksByY = new LinkedHashMap<>();
            if (interiorFloorArea > 0L) {
                for (PlayerMemory.ConstructionBlock block : blocks) {
                    if (block.y > minY && block.y < maxY
                            && block.x > minX && block.x < maxX
                            && block.z > minZ && block.z < maxZ) {
                        interiorBlocksByY.merge(block.y, 1, Integer::sum);
                    }
                }
            }
            for (Map.Entry<Integer, Integer> layer : interiorBlocksByY.entrySet()) {
                if (layer.getValue() / (double) interiorFloorArea >= 0.55D) floorLayers++;
            }

            double floorCoverage = floorArea <= 0L ? 0.0D
                    : Math.min(1.0D, floorBlocks / (double) floorArea);
            double roofCoverage = floorArea <= 0L ? 0.0D
                    : Math.min(1.0D, roofBlocks / (double) floorArea);
            double wallCoverage = wallArea <= 0L ? 0.0D
                    : Math.min(1.0D, occupiedWallFaceCells / (double) wallArea);

            RoomAnalyzer.Analysis interior = RoomAnalyzer.analyze(
                    blocks, minX, minY, minZ, maxX, maxY, maxZ, interiorBudget
            );
            boolean enclosedBuilding = buildingCandidate
                    && interior.complete() && interior.enclosedVoidCells() > 0
                    && floorCoverage >= 0.55D
                    && roofCoverage >= 0.55D
                    && wallCoverage >= 0.40D;
            boolean openBuilding = buildingCandidate
                    && interior.complete() && interior.unoccupiedCells() > 0
                    && floorCoverage >= 0.35D
                    && wallCoverage >= 0.30D
                    && (roofCoverage >= 0.25D || doorCount > 0 || windowBlockCount > 0);

            String semanticKind = enclosedBuilding ? "ENCLOSED_BUILDING"
                    : openBuilding ? "OPEN_BUILDING"
                    : buildingCandidate ? "BUILDING_CANDIDATE" : "STRUCTURE";

            int smallestSpan = Math.min(spanX, Math.min(spanY, spanZ));
            double baseConfidence = buildingCandidate
                    ? 0.35D + 0.45D * ratio(blockCount, 64.0D)
                            + 0.20D * ratio(smallestSpan, 8.0D)
                    : 0.15D + 0.45D * ratio(blockCount, 64.0D);
            double shellQuality = (floorCoverage + roofCoverage + wallCoverage) / 3.0D;
            double confidence = buildingCandidate
                    ? Math.min(0.98D, 0.65D * baseConfidence
                            + 0.20D * shellQuality
                            + (enclosedBuilding ? 0.15D : 0.0D))
                    : Math.min(0.60D, baseConfidence);

            return new Structure(
                    dimension, blockCount,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    blockTypes.size(), blockStates.size(),
                    firstPlacedAt, lastPlacedAt,
                    buildingCandidate, confidence,
                    floorCoverage, roofCoverage, wallCoverage,
                    floorLayers, doorCount, windowBlockCount,
                    stairBlockCount, slabBlockCount,
                    interior.complete(), interior.unoccupiedCells(),
                    interior.enclosedVoidCells(), semanticKind,
                    interior.rooms(), interior.doorLinks()
            );
        }
    }
}
