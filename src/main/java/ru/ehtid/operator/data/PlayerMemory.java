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
    /** Recent mined terrain voxels, keyed by dimension and block coordinates. */
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
        if (brokenBlockTypes == null) brokenBlockTypes = new LinkedHashMap<>();
        if (minedBlockTypes == null) minedBlockTypes = new LinkedHashMap<>();
        if (miningDirections == null) miningDirections = new LinkedHashMap<>();
        if (dimensionVisits == null) dimensionVisits = new LinkedHashMap<>();
        if (miningDepthBands == null) miningDepthBands = new LinkedHashMap<>();
        trim(route, MAX_ROUTE_SAMPLES);
        trim(mineHistory, MAX_MINE_SAMPLES);
        trimTunnelCells();
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

    private static <K, V> void trimOldest(Map<K, V> map, int maximum) {
        while (map.size() > maximum) {
            var iterator = map.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
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
