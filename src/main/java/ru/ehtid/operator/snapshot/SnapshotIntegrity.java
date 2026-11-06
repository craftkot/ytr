package ru.ehtid.operator.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Exact, deterministic checksum of the metadata and saved block states/NBT. */
public final class SnapshotIntegrity {
    private SnapshotIntegrity() { }

    public static String fingerprint(StructureSnapshot snapshot) {
        if (snapshot == null || snapshot.blocks == null) throw new IllegalArgumentException("Invalid snapshot");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, Integer.toString(snapshot.formatVersion));
            field(digest, snapshot.snapshotId);
            field(digest, snapshot.playerUuid);
            field(digest, snapshot.dimension);
            field(digest, Long.toString(snapshot.capturedAtTick));
            for (int n : new int[]{snapshot.minX, snapshot.minY, snapshot.minZ,
                    snapshot.maxX, snapshot.maxY, snapshot.maxZ, snapshot.blockEntityCount,
                    snapshot.blockEntitySnbtBytes}) {
                field(digest, Integer.toString(n));
            }
            List<StructureSnapshot.Entry> entries = snapshot.blocks.stream()
                    .sorted(Comparator.comparingInt(StructureSnapshot.Entry::x)
                            .thenComparingInt(StructureSnapshot.Entry::y)
                            .thenComparingInt(StructureSnapshot.Entry::z))
                    .toList();
            field(digest, Integer.toString(entries.size()));
            for (StructureSnapshot.Entry e : entries) {
                field(digest, Integer.toString(e.x()));
                field(digest, Integer.toString(e.y()));
                field(digest, Integer.toString(e.z()));
                field(digest, e.blockId());
                field(digest, e.blockState());
                field(digest, e.blockEntitySnbt());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public static boolean valid(StructureSnapshot snapshot) {
        return snapshot != null && snapshot.sha256 != null
                && snapshot.sha256.equals(fingerprint(snapshot));
    }

    private static void field(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
