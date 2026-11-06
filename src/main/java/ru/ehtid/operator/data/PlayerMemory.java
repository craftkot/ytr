package ru.ehtid.operator.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent, bounded behavioural profile for one player.
 * This class deliberately has no Minecraft dependencies so its logic can be unit-tested in isolation.
 */
public final class PlayerMemory {
    public static final int MAX_ROUTE_SAMPLES = 512;
    public static final int MAX_MINE_SAMPLES = 2048;
    public static final int MAX_TUNNEL_CELLS = 4096;
    public static final int MAX_CONSTRUCTION_BLOCKS = 8192;
    public static final int MAX_BLOCK_STATE_LENGTH = 512;
    public static final int MAX_COUNTER_KEYS = 256;
    public static final String TUNNEL_SOURCE_MINED = "mined";
    public static final String TUNNEL_SOURCE_OBSERVED_AIR = "observed_air";

    public String uuid = "";
    public String name = "Unknown";

    /** World game-time values, not wall-clock timestamps. */
    public long firstSeenAt = -1L;
    public long lastSeenAt = -1L;
    /** Active server ticks observed while this player is online. */
    public long observedTicks = 0L;

    public long blocksBroken = 0L;
    public long rockBlocksBroken = 0L;
    public long movementSamples = 0L;
    public double distanceTravelled = 0.0D;
    public long miningSessions = 0L;
    /** Number of terrain cells cleared while tunnel mapping was active. */
    public long tunnelBlocksCleared = 0L;

    /** Persisted output of the Operator's non-mutating decision planner. */
    public String operatorPhase = "OBSERVING";
    public String operatorArchetype = "UNKNOWN";
    public String plannedOperatorEvent = "NONE";
    public String operatorReason = "Waiting for the observation gates.";
    public double operatorConfidence = 0.0D;
    public long lastBrainEvaluationAt = -1L;
    public long lastOperatorDecisionAt = -1L;
    public long operatorDecisionCount = 0L;

    /** Event-derived player-placed block history and its latest geometric analysis. */
    public Map<String, ConstructionBlock> constructionBlocks = new LinkedHashMap<>();
    public long blocksPlaced = 0L;
    public long constructionBlocksRemoved = 0L;
    public long lastConstructionChangeAt = -1L;
    public long lastConstructionAnalysisAt = -1L;
    public boolean constructionAnalysisDirty = false;
    public int recognizedStructureCount = 0;
    public int recognizedBuildingCount = 0;
    public int largestStructureBlockCount = 0;
    public int largestBuildingBlockCount = 0;
    public double constructionComplexity = 0.0D;
    /** Best geometric shell metrics across this player's building candidates. */
    public int recognizedEnclosedBuildingCount = 0;
    public int largestEnclosedVoidCells = 0;
    public int largestDoorCount = 0;
    public int largestWindowBlockCount = 0;
    public int largestStairBlockCount = 0;
    public int largestSlabBlockCount = 0;
    public int largestFloorLayerCount = 0;
    /** Summary of bounded enclosed-room inference, not authoritative world geometry. */
    public int recognizedRoomCount = 0;
    public int recognizedRoomConnections = 0;
    public int recognizedFurnishedRoomCount = 0;
    public int largestRoomCellCount = 0;
    public double largestFloorCoverage = 0.0D;
    public double largestRoofCoverage = 0.0D;
    public double largestWallCoverage = 0.0D;

    public boolean hasLastPosition = false;
    public double lastX;
    public double lastY;
    public double lastZ;
    public String lastDimension = "";

    public boolean hasLastRouteSample = false;
    public double lastRouteX;
    public double lastRouteY;
    public double lastRouteZ;
    public String lastRouteDimension = "";

    public boolean hasLastMinePosition = false;
    public int lastMineX;
    public int lastMineY;
    public int lastMineZ;
    public long lastMineTick = -1L;
    public String lastMineDimension = "";
    public int currentMiningChain = 0;
    public int longestMiningChain = 0;

    public List<RouteSample> route = new ArrayList<>();
    public List<MineSample> mineHistory = new ArrayList<>();
    /** Recent mined and locally discovered air voxels, keyed by dimension and block coordinates. */
    public Map<String, TunnelCell> tunnelCells = new LinkedHashMap<>();
    public Map<String, Long> brokenBlockTypes = new LinkedHashMap<>();
    public Map<String, Long> minedBlockTypes = new LinkedHashMap<>();
    public Map<String, Long> miningDirections = new LinkedHashMap<>();
    public Map<String, Long> dimensionVisits = new LinkedHashMap<>();
    public Map<String, Long> miningDepthBands = new LinkedHashMap<>();

    public void normalize() {
        if (route == null) route = new ArrayList<>();
        if (mineHistory == null) mineHistory = new ArrayList<>();
        if (tunnelCells == null) {
            tunnelCells = new LinkedHashMap<>();
        } else {
            // Gson's generic Map adapter does not guarantee insertion order.
            // Rebuild oldest-to-newest from the stored game ticks before applying the cap.
            List<Map.Entry<String, TunnelCell>> orderedCells = new ArrayList<>(tunnelCells.entrySet());
            orderedCells.removeIf(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getValue().dimension == null);
            orderedCells.sort(Comparator.comparingLong(entry -> entry.getValue().gameTick));
            Map<String, TunnelCell> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, TunnelCell> entry : orderedCells) {
                TunnelCell cell = entry.getValue();
                if (cell.source == null || cell.source.isBlank()) cell.source = TUNNEL_SOURCE_MINED;
                if (!TUNNEL_SOURCE_MINED.equals(cell.source)
                        && !TUNNEL_SOURCE_OBSERVED_AIR.equals(cell.source)) {
                    cell.source = TUNNEL_SOURCE_MINED;
                }
                ordered.put(entry.getKey(), cell);
            }
            tunnelCells = ordered;
        }
        if (constructionBlocks == null) {
            constructionBlocks = new LinkedHashMap<>();
        } else {
            List<Map.Entry<String, ConstructionBlock>> orderedBlocks =
                    new ArrayList<>(constructionBlocks.entrySet());
            orderedBlocks.removeIf(entry -> entry.getKey() == null || entry.getValue() == null
                    || entry.getValue().dimension == null || entry.getValue().blockId == null);
            orderedBlocks.sort(Comparator.comparingLong(entry -> entry.getValue().gameTick));
            Map<String, ConstructionBlock> ordered = new LinkedHashMap<>();
            for (Map.Entry<String, ConstructionBlock> entry : orderedBlocks) {
                ordered.put(entry.getKey(), entry.getValue());
            }
            for (ConstructionBlock block : ordered.values()) {
                if (block.blockState == null) block.blockState = "";
                if (block.blockState.length() > MAX_BLOCK_STATE_LENGTH) {
                    block.blockState = block.blockState.substring(0, MAX_BLOCK_STATE_LENGTH);
                }
            }
            constructionBlocks = ordered;
        }
        if (brokenBlockTypes == null) brokenBlockTypes = new LinkedHashMap<>();
        if (minedBlockTypes == null) minedBlockTypes = new LinkedHashMap<>();
        if (miningDirections == null) miningDirections = new LinkedHashMap<>();
        if (dimensionVisits == null) dimensionVisits = new LinkedHashMap<>();
        if (miningDepthBands == null) miningDepthBands = new LinkedHashMap<>();
        trim(route, MAX_ROUTE_SAMPLES);
        trim(mineHistory, MAX_MINE_SAMPLES);
        trimTunnelCells();
        trimConstructionBlocks();
        trimMap(brokenBlockTypes, MAX_COUNTER_KEYS);
        trimMap(minedBlockTypes, MAX_COUNTER_KEYS);
        trimMap(miningDirections, MAX_COUNTER_KEYS);
        trimMap(dimensionVisits, MAX_COUNTER_KEYS);
        trimMap(miningDepthBands, MAX_COUNTER_KEYS);
        observedTicks = Math.max(0L, observedTicks);
        blocksBroken = Math.max(0L, blocksBroken);
        rockBlocksBroken = Math.max(0L, rockBlocksBroken);
        movementSamples = Math.max(0L, movementSamples);
        miningSessions = Math.max(0L, miningSessions);
        tunnelBlocksCleared = Math.max(0L, tunnelBlocksCleared);
        distanceTravelled = Math.max(0.0D, distanceTravelled);
        currentMiningChain = Math.max(0, currentMiningChain);
        longestMiningChain = Math.max(currentMiningChain, longestMiningChain);
        if (operatorPhase == null || operatorPhase.isBlank()) operatorPhase = "OBSERVING";
        if (operatorArchetype == null || operatorArchetype.isBlank()) operatorArchetype = "UNKNOWN";
        if (plannedOperatorEvent == null || plannedOperatorEvent.isBlank()) plannedOperatorEvent = "NONE";
        if (operatorReason == null) operatorReason = "";
        if (!Double.isFinite(operatorConfidence)) operatorConfidence = 0.0D;
        operatorConfidence = Math.max(0.0D, Math.min(1.0D, operatorConfidence));
        lastBrainEvaluationAt = Math.max(-1L, lastBrainEvaluationAt);
        lastOperatorDecisionAt = Math.max(-1L, lastOperatorDecisionAt);
        operatorDecisionCount = Math.max(0L, operatorDecisionCount);
        blocksPlaced = Math.max(0L, blocksPlaced);
        constructionBlocksRemoved = Math.max(0L, constructionBlocksRemoved);
        lastConstructionChangeAt = Math.max(-1L, lastConstructionChangeAt);
        lastConstructionAnalysisAt = Math.max(-1L, lastConstructionAnalysisAt);
        recognizedStructureCount = Math.max(0, recognizedStructureCount);
        recognizedBuildingCount = Math.max(0, recognizedBuildingCount);
        largestStructureBlockCount = Math.max(0, largestStructureBlockCount);
        largestBuildingBlockCount = Math.max(0, largestBuildingBlockCount);
        if (!Double.isFinite(constructionComplexity)) constructionComplexity = 0.0D;
        constructionComplexity = Math.max(0.0D, Math.min(1.0D, constructionComplexity));
        recognizedEnclosedBuildingCount = Math.max(0, recognizedEnclosedBuildingCount);
        largestEnclosedVoidCells = Math.max(0, largestEnclosedVoidCells);
        largestDoorCount = Math.max(0, largestDoorCount);
        largestWindowBlockCount = Math.max(0, largestWindowBlockCount);
        largestStairBlockCount = Math.max(0, largestStairBlockCount);
        largestSlabBlockCount = Math.max(0, largestSlabBlockCount);
        largestFloorLayerCount = Math.max(0, largestFloorLayerCount);
        recognizedRoomCount = Math.max(0, recognizedRoomCount);
        recognizedRoomConnections = Math.max(0, recognizedRoomConnections);
        recognizedFurnishedRoomCount = Math.max(0, recognizedFurnishedRoomCount);
        largestRoomCellCount = Math.max(0, largestRoomCellCount);
        largestFloorCoverage = normalizeRatio(largestFloorCoverage);
        largestRoofCoverage = normalizeRatio(largestRoofCoverage);
        largestWallCoverage = normalizeRatio(largestWallCoverage);
    }

    public void recordActiveTick(long gameTick) {
        if (firstSeenAt < 0L) firstSeenAt = gameTick;
        lastSeenAt = gameTick;
        observedTicks++;
    }

    /**
     * Records a sampled position. Distance is accumulated between every sample,
     * while the bounded route keeps only meaningful movement and dimension changes.
     */
    public void recordMovement(double x, double y, double z, String dimension, long gameTick) {
        if (firstSeenAt < 0L) firstSeenAt = gameTick;
        lastSeenAt = gameTick;
        movementSamples++;

        if (!hasLastPosition) {
            setLastPosition(x, y, z, dimension);
            addRouteSample(x, y, z, dimension, gameTick);
            dimensionVisits.merge(dimension, 1L, Long::sum);
            return;
        }

        if (!dimension.equals(lastDimension)) {
            setLastPosition(x, y, z, dimension);
            addRouteSample(x, y, z, dimension, gameTick);
            dimensionVisits.merge(dimension, 1L, Long::sum);
            return;
        }

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (Double.isFinite(distance)) {
            distanceTravelled += distance;
        }
        setLastPosition(x, y, z, dimension);

        if (!hasLastRouteSample || !dimension.equals(lastRouteDimension)
                || squaredDistance(x, y, z, lastRouteX, lastRouteY, lastRouteZ) >= 1.0D) {
            addRouteSample(x, y, z, dimension, gameTick);
        }
    }

    public void recordBrokenBlock(int x, int y, int z, String dimension, String blockId,
                                  boolean rockLike, long gameTick) {
        recordBrokenBlock(x, y, z, dimension, blockId, rockLike, rockLike, gameTick);
    }

    public void recordBrokenBlock(int x, int y, int z, String dimension, String blockId,
                                  boolean rockLike, boolean tunnelMaterial, long gameTick) {
        if (firstSeenAt < 0L) firstSeenAt = gameTick;
        lastSeenAt = gameTick;
        blocksBroken++;
        incrementBounded(brokenBlockTypes, blockId);

        if (tunnelMaterial) recordTunnelCell(x, y, z, dimension, blockId, gameTick);

        if (!rockLike) {
            currentMiningChain = 0;
            hasLastMinePosition = false;
            lastMineTick = -1L;
            lastMineDimension = "";
            return;
        }

        rockBlocksBroken++;
        incrementBounded(minedBlockTypes, blockId);
        mineHistory.add(new MineSample(x, y, z, dimension, blockId, gameTick));
        trim(mineHistory, MAX_MINE_SAMPLES);
        incrementBounded(miningDepthBands, depthBand(y));

        boolean continuesPreviousChain = false;
        if (hasLastMinePosition && dimension.equals(lastMineDimension)) {
            int dx = x - lastMineX;
            int dy = y - lastMineY;
            int dz = z - lastMineZ;
            int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            long ticksSinceLastBlock = gameTick - lastMineTick;
            continuesPreviousChain = manhattan >= 1 && manhattan <= 5
                    && ticksSinceLastBlock >= 0L && ticksSinceLastBlock <= 100L;
            if (continuesPreviousChain) {
                currentMiningChain++;
                recordDirection(dx, dy, dz);
            }
        }

        if (!continuesPreviousChain) {
            currentMiningChain = 1;
            miningSessions++;
        }
        longestMiningChain = Math.max(longestMiningChain, currentMiningChain);

        lastMineX = x;
        lastMineY = y;
        lastMineZ = z;
        lastMineDimension = dimension;
        lastMineTick = gameTick;
        hasLastMinePosition = true;
    }

    public int getTunnelCellCount() {
        return tunnelCells == null ? 0 : tunnelCells.size();
    }

    public int getMinedTunnelCellCount() {
        if (tunnelCells == null) return 0;
        return (int) tunnelCells.values().stream()
                .filter(cell -> cell != null && TUNNEL_SOURCE_MINED.equals(cell.source))
                .count();
    }

    public int getObservedAirCellCount() {
        if (tunnelCells == null) return 0;
        return (int) tunnelCells.values().stream()
                .filter(cell -> cell != null && TUNNEL_SOURCE_OBSERVED_AIR.equals(cell.source))
                .count();
    }

    /**
     * Adds an air voxel found by the bounded nearby-world scanner.
     * Scanned cells never overwrite mined evidence and never increment mined-block totals.
     */
    public boolean recordDiscoveredAir(int x, int y, int z, String dimension, long gameTick) {
        String key = tunnelCellKey(dimension, x, y, z);
        if (tunnelCells.containsKey(key)) return false;
        if (tunnelCells.size() >= MAX_TUNNEL_CELLS && !evictOldestObservedAirCell()) return false;
        tunnelCells.put(key, new TunnelCell(
                x, y, z, dimension, "minecraft:air", gameTick, TUNNEL_SOURCE_OBSERVED_AIR
        ));
        return true;
    }

    public boolean hasTunnelCell(String dimension, int x, int y, int z) {
        return tunnelCells != null && tunnelCells.containsKey(tunnelCellKey(dimension, x, y, z));
    }

    public boolean removeTunnelCell(String dimension, int x, int y, int z) {
        return tunnelCells != null && tunnelCells.remove(tunnelCellKey(dimension, x, y, z)) != null;
    }

    private static String tunnelCellKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "," + y + "," + z;
    }

    private boolean evictOldestObservedAirCell() {
        var iterator = tunnelCells.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TunnelCell> entry = iterator.next();
            if (entry.getValue() != null
                    && TUNNEL_SOURCE_OBSERVED_AIR.equals(entry.getValue().source)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void trimTunnelCells() {
        while (tunnelCells.size() > MAX_TUNNEL_CELLS) {
            if (!evictOldestObservedAirCell()) {
                var iterator = tunnelCells.keySet().iterator();
                if (!iterator.hasNext()) return;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void recordTunnelCell(int x, int y, int z, String dimension, String blockId, long gameTick) {
        String key = tunnelCellKey(dimension, x, y, z);
        tunnelCells.remove(key);
        tunnelCells.put(key, new TunnelCell(
                x, y, z, dimension, blockId, gameTick, TUNNEL_SOURCE_MINED
        ));
        tunnelBlocksCleared++;
        trimTunnelCells();
    }

    /** Records a block confirmed to have been placed by this player. */
    public boolean recordPlacedBlock(int x, int y, int z, String dimension,
                                     String blockId, long gameTick) {
        return recordPlacedBlock(x, y, z, dimension, blockId, "", gameTick);
    }

    /** Records the confirmed block type and its serialized BlockState properties. */
    public boolean recordPlacedBlock(int x, int y, int z, String dimension,
                                     String blockId, String blockState, long gameTick) {
        if (dimension == null || blockId == null || blockId.isBlank()) return false;
        String key = constructionBlockKey(dimension, x, y, z);
        constructionBlocks.remove(key);
        constructionBlocks.put(key, new ConstructionBlock(
                x, y, z, dimension, blockId, normalizeBlockState(blockState), gameTick
        ));
        blocksPlaced++;
        lastConstructionChangeAt = gameTick;
        constructionAnalysisDirty = true;
        trimConstructionBlocks();
        return true;
    }

    /** Refreshes orientation/open/waterlogged properties without counting a new placement. */
    public boolean updatePlacedBlockState(int x, int y, int z, String dimension,
                                           String blockState, long gameTick) {
        if (constructionBlocks == null) return false;
        ConstructionBlock block = constructionBlocks.get(constructionBlockKey(dimension, x, y, z));
        if (block == null) return false;
        String normalized = normalizeBlockState(blockState);
        if (normalized.equals(block.blockState)) return false;
        block.blockState = normalized;
        lastConstructionChangeAt = gameTick;
        constructionAnalysisDirty = true;
        return true;
    }

    public void updateConstructionSemanticsSummary(
            int enclosedBuildingCount,
            int enclosedVoidCells,
            int doorCount,
            int windowBlockCount,
            int stairBlockCount,
            int slabBlockCount,
            int floorLayerCount,
            double floorCoverage,
            double roofCoverage,
            double wallCoverage
    ) {
        recognizedEnclosedBuildingCount = Math.max(0, enclosedBuildingCount);
        largestEnclosedVoidCells = Math.max(0, enclosedVoidCells);
        largestDoorCount = Math.max(0, doorCount);
        largestWindowBlockCount = Math.max(0, windowBlockCount);
        largestStairBlockCount = Math.max(0, stairBlockCount);
        largestSlabBlockCount = Math.max(0, slabBlockCount);
        largestFloorLayerCount = Math.max(0, floorLayerCount);
        largestFloorCoverage = normalizeRatio(floorCoverage);
        largestRoofCoverage = normalizeRatio(roofCoverage);
        largestWallCoverage = normalizeRatio(wallCoverage);
    }

    public void updateRoomSummary(int roomCount, int links, int furnishedRooms, int largestVolume) {
        recognizedRoomCount = Math.max(0, roomCount);
        recognizedRoomConnections = Math.max(0, links);
        recognizedFurnishedRoomCount = Math.max(0, furnishedRooms);
        largestRoomCellCount = Math.max(0, largestVolume);
    }

    private static String normalizeBlockState(String blockState) {
        if (blockState == null || blockState.isBlank()) return "";
        return blockState.length() <= MAX_BLOCK_STATE_LENGTH
                ? blockState : blockState.substring(0, MAX_BLOCK_STATE_LENGTH);
    }

    private static double normalizeRatio(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /** Invalidates a tracked placed block after any player breaks or replaces it. */
    public boolean removePlacedBlock(int x, int y, int z, String dimension, long gameTick) {
        if (constructionBlocks == null
                || constructionBlocks.remove(constructionBlockKey(dimension, x, y, z)) == null) {
            return false;
        }
        constructionBlocksRemoved++;
        lastConstructionChangeAt = gameTick;
        constructionAnalysisDirty = true;
        return true;
    }

    public void updateConstructionSummary(int structureCount, int buildingCount,
                                          int largestStructure, int largestBuilding,
                                          double complexity, long gameTick) {
        recognizedStructureCount = Math.max(0, structureCount);
        recognizedBuildingCount = Math.max(0, buildingCount);
        largestStructureBlockCount = Math.max(0, largestStructure);
        largestBuildingBlockCount = Math.max(0, largestBuilding);
        constructionComplexity = Double.isFinite(complexity)
                ? Math.max(0.0D, Math.min(1.0D, complexity)) : 0.0D;
        lastConstructionAnalysisAt = gameTick;
        constructionAnalysisDirty = false;
    }

    public int getConstructionBlockCount() {
        return constructionBlocks == null ? 0 : constructionBlocks.size();
    }

    private static String constructionBlockKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "," + y + "," + z;
    }

    private void trimConstructionBlocks() {
        while (constructionBlocks.size() > MAX_CONSTRUCTION_BLOCKS) {
            var iterator = constructionBlocks.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    public boolean personalObservationComplete(long requiredActiveTicks) {
        return observedTicks >= requiredActiveTicks;
    }

    public boolean hasEnoughMiningData() {
        return rockBlocksBroken >= 20L && mineHistory.size() >= 10;
    }

    public String dominantMiningDirection() {
        return dominantKey(miningDirections, "not enough data");
    }

    public String mostMinedBlockType() {
        return dominantKey(minedBlockTypes, "not enough data");
    }

    public String mostVisitedDimension() {
        return dominantKey(dimensionVisits, "not enough data");
    }

    private void setLastPosition(double x, double y, double z, String dimension) {
        lastX = x;
        lastY = y;
        lastZ = z;
        lastDimension = dimension;
        hasLastPosition = true;
    }

    private void addRouteSample(double x, double y, double z, String dimension, long gameTick) {
        route.add(new RouteSample(x, y, z, dimension, gameTick));
        trim(route, MAX_ROUTE_SAMPLES);
        lastRouteX = x;
        lastRouteY = y;
        lastRouteZ = z;
        lastRouteDimension = dimension;
        hasLastRouteSample = true;
    }

    private void recordDirection(int dx, int dy, int dz) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);
        if (ax + ay + az == 0) return;

        String direction;
        if (ax >= ay && ax >= az) {
            direction = dx > 0 ? "east" : "west";
        } else if (az >= ax && az >= ay) {
            direction = dz > 0 ? "south" : "north";
        } else {
            direction = dy > 0 ? "up" : "down";
        }
        incrementBounded(miningDirections, direction);
    }

    private static String depthBand(int y) {
        if (y >= 64) return "high_64_plus";
        if (y >= 0) return "surface_0_63";
        if (y >= -32) return "deep_minus1_minus32";
        return "deepslate_minus33_below";
    }

    private static double squaredDistance(double x1, double y1, double z1,
                                          double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void incrementBounded(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
        if (map.size() > MAX_COUNTER_KEYS) {
            String leastUsed = map.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (leastUsed != null) map.remove(leastUsed);
        }
    }

    private static String dominantKey(Map<String, Long> map, String fallback) {
        return map.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(fallback);
    }

    private static <T> void trim(List<T> list, int maximum) {
        while (list.size() > maximum) list.remove(0);
    }

    private static void trimMap(Map<String, Long> map, int maximum) {
        while (map.size() > maximum) {
            String leastUsed = map.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (leastUsed == null) return;
            map.remove(leastUsed);
        }
    }

    public static final class RouteSample {
        public double x;
        public double y;
        public double z;
        public String dimension;
        public long gameTick;

        public RouteSample() { }

        public RouteSample(double x, double y, double z, String dimension, long gameTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.gameTick = gameTick;
        }
    }

    public static final class TunnelCell {
        public int x;
        public int y;
        public int z;
        public String dimension;
        public String blockId;
        public long gameTick;
        /** mined or observed_air; null legacy values are normalized to mined. */
        public String source;

        public TunnelCell() { }

        public TunnelCell(int x, int y, int z, String dimension, String blockId, long gameTick) {
            this(x, y, z, dimension, blockId, gameTick, TUNNEL_SOURCE_MINED);
        }

        public TunnelCell(int x, int y, int z, String dimension, String blockId,
                          long gameTick, String source) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockId = blockId;
            this.gameTick = gameTick;
            this.source = source;
        }
    }

    public static final class ConstructionBlock {
        public int x;
        public int y;
        public int z;
        public String dimension;
        public String blockId;
        /** Serialized block identifier plus property values (orientation, half, open, etc.). */
        public String blockState = "";
        public long gameTick;

        public ConstructionBlock() { }

        public ConstructionBlock(int x, int y, int z, String dimension,
                                 String blockId, long gameTick) {
            this(x, y, z, dimension, blockId, "", gameTick);
        }

        public ConstructionBlock(int x, int y, int z, String dimension,
                                 String blockId, String blockState, long gameTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockId = blockId;
            this.blockState = normalizeBlockState(blockState);
            this.gameTick = gameTick;
        }
    }

    public static final class MineSample {
        public int x;
        public int y;
        public int z;
        public String dimension;
        public String blockId;
        public long gameTick;

        public MineSample() { }

        public MineSample(int x, int y, int z, String dimension, String blockId, long gameTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.blockId = blockId;
            this.gameTick = gameTick;
        }
    }
}
