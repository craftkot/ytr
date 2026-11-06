package ru.ehtid.operator.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/**
 * SHA-256 of the exact before-images and immutable transaction metadata.
 * This is integrity detection, not authentication against hostile file edits.
 */
public final class TransactionIntegrity {
    private TransactionIntegrity() { }

    public static String fingerprint(TransactionJournal journal) {
        if (!TransactionJournal.hasCompleteDisjointPreimages(journal))
            throw new IllegalArgumentException("Incomplete or overlapping transaction preimages");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, journal.formatVersion);
            field(digest, journal.journalId);
            field(digest, journal.ownerUuid);
            field(digest, journal.sourceSnapshotId);
            field(digest, journal.sourceSnapshotSha256);
            field(digest, journal.dimension);
            field(digest, journal.phase);
            field(digest, journal.capturedAtTick);
            field(digest, journal.offsetX);
            field(digest, journal.offsetY);
            field(digest, journal.offsetZ);
            field(digest, journal.blockEntityCount);
            field(digest, journal.totalNbtBytes);
            region(digest, journal.source);
            region(digest, journal.target);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static boolean valid(TransactionJournal journal) {
        if (journal == null || journal.sha256 == null
                || !TransactionJournal.hasCompleteDisjointPreimages(journal)) return false;
        return journal.sha256.equals(fingerprint(journal));
    }

    private static void region(MessageDigest digest, TransactionJournal.Region region) {
        field(digest, region.minX);
        field(digest, region.minY);
        field(digest, region.minZ);
        field(digest, region.maxX);
        field(digest, region.maxY);
        field(digest, region.maxZ);
        field(digest, region.before.size());
        region.before.stream().sorted(Comparator
                .comparingInt(TransactionJournal.Cell::x)
                .thenComparingInt(TransactionJournal.Cell::y)
                .thenComparingInt(TransactionJournal.Cell::z))
                .forEach(cell -> {
                    field(digest, cell.x());
                    field(digest, cell.y());
                    field(digest, cell.z());
                    field(digest, cell.blockId());
                    field(digest, cell.blockState());
                    field(digest, cell.blockEntitySnbt());
                });
    }

    private static void field(MessageDigest digest, long value) {
        field(digest, Long.toString(value));
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
