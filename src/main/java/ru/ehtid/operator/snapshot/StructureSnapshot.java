package ru.ehtid.operator.snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * A server-local, read-only preimage of observed construction blocks.
 *
 * Data may contain inventories and other private block entity fields. Do not
 * print NBT in chat or upload snapshots to external telemetry.
 */
public final class StructureSnapshot {
    public int formatVersion = 1;
    public String snapshotId = "";
    public String playerUuid = "";
    public String dimension = "";
    public long capturedAtTick;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int blockEntityCount;
    public int blockEntitySnbtBytes;
    public String sha256 = "";
    public List<Entry> blocks = new ArrayList<>();

    public StructureSnapshot() { }

    public record Entry(int x, int y, int z, String blockId, String blockState,
                        String blockEntitySnbt) { }

    public int blockCount() {
        return blocks == null ? 0 : blocks.size();
    }

    public String bounds() {
        return minX + "," + minY + "," + minZ
                + " -> " + maxX + "," + maxY + "," + maxZ;
    }
}
