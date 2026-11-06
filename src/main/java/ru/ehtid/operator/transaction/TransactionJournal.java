package ru.ehtid.operator.transaction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable BEFORE images for both source and target world regions.
 *
 * This release only creates PREPARED journals. No journal phase authorizes
 * block placement, deletion or rollback by itself.
 */
public final class TransactionJournal {
    public static final int MAX_CELLS_PER_REGION = 4096;
    public static final int MAX_TOTAL_CELLS = 8192;
    public static final int MAX_NBT_BYTES = 2 * 1024 * 1024;

    public int formatVersion = 1;
    public String journalId = "";
    public String ownerUuid = "";
    public String sourceSnapshotId = "";
    public String sourceSnapshotSha256 = "";
    public String dimension = "";
    public String phase = "PREPARED";
    public long capturedAtTick;
    public int offsetX, offsetY, offsetZ;
    public Region source = new Region();
    public Region target = new Region();
    public int blockEntityCount;
    public int totalNbtBytes;
    public String sha256 = "";

    public static final class Region {
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public List<Cell> before = new ArrayList<>();

        public Region() { }

        public long volume() {
            long sx = (long) maxX - minX + 1;
            long sy = (long) maxY - minY + 1;
            long sz = (long) maxZ - minZ + 1;
            if (sx <= 0 || sy <= 0 || sz <= 0) return -1;
            return sx * sy * sz;
        }

        public String bounds() {
            return minX + "," + minY + "," + minZ + " -> "
                    + maxX + "," + maxY + "," + maxZ;
        }
    }

    public record Cell(int x, int y, int z,
                       String blockId, String blockState, String blockEntitySnbt) { }

    /**
     * Independent in-memory coverage guard before persistence or future replay.
     * Source and target must be disjoint and every occupied/unoccupied position
     * inside both AABBs must have exactly one captured preimage.
     */
    public static boolean hasCompleteDisjointPreimages(TransactionJournal journal) {
        if (journal == null || journal.source == null || journal.target == null
                || journal.source.before == null || journal.target.before == null
                || journal.source.volume() <= 0 || journal.target.volume() <= 0
                || journal.source.volume() > MAX_CELLS_PER_REGION
                || journal.target.volume() > MAX_CELLS_PER_REGION
                || journal.source.volume() + journal.target.volume() > MAX_TOTAL_CELLS)
            return false;

        if (intersects(journal.source.minX, journal.source.maxX,
                journal.target.minX, journal.target.maxX)
                && intersects(journal.source.minY, journal.source.maxY,
                journal.target.minY, journal.target.maxY)
                && intersects(journal.source.minZ, journal.source.maxZ,
                journal.target.minZ, journal.target.maxZ))
            return false;

        return full(journal.source) && full(journal.target);
    }

    private static boolean full(Region region) {
        if (region.before.size() != region.volume()) return false;
        Set<Position> seen = new HashSet<>();
        for (Cell cell : region.before) {
            if (cell == null || cell.blockId == null || cell.blockId.isBlank()
                    || cell.blockState == null) return false;
            if (cell.x < region.minX || cell.x > region.maxX
                    || cell.y < region.minY || cell.y > region.maxY
                    || cell.z < region.minZ || cell.z > region.maxZ
                    || !seen.add(new Position(cell.x, cell.y, cell.z))) return false;
        }
        return true;
    }

    private static boolean intersects(long a, long b, long c, long d) {
        return a <= d && c <= b;
    }

    public static List<Cell> reversePreimages(TransactionJournal journal) {
        if (!hasCompleteDisjointPreimages(journal))
            throw new IllegalArgumentException("Transaction has incomplete preimages");
        List<Cell> result = new ArrayList<>(
                journal.source.before.size() + journal.target.before.size());
        result.addAll(journal.target.before);
        result.addAll(journal.source.before);
        result.sort(Comparator.comparingInt(Cell::x)
                .thenComparingInt(Cell::y).thenComparingInt(Cell::z));
        return List.copyOf(result);
    }

    private record Position(int x, int y, int z) { }
}
