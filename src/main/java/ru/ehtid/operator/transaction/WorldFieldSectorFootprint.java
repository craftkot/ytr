package ru.ehtid.operator.transaction;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * All 64x64 world-field sectors touched by the source AND target preimages.
 * V2 reservations prevent a later field event from treating the neighboring
 * source sector as independent after a cross-boundary write.
 */
public final class WorldFieldSectorFootprint {
    private WorldFieldSectorFootprint() { }

    public static Set<String> sectors(TransactionJournal journal) {
        if (journal == null || !SandboxTransactionEngine.isTunnelFold(journal))
            return Set.of();
        Set<String> result = new LinkedHashSet<>();
        add(result, journal.dimension, journal.source);
        add(result, journal.dimension, journal.target);
        return result.size() >= 1 && result.size() <= 4
                ? Set.copyOf(result) : Set.of();
    }

    private static void add(Set<String> out, String dimension,
                            TransactionJournal.Region region) {
        int lowX = Math.floorDiv(region.minX, WorldFieldPolicy.SECTOR_WIDTH);
        int highX = Math.floorDiv(region.maxX, WorldFieldPolicy.SECTOR_WIDTH);
        int lowZ = Math.floorDiv(region.minZ, WorldFieldPolicy.SECTOR_WIDTH);
        int highZ = Math.floorDiv(region.maxZ, WorldFieldPolicy.SECTOR_WIDTH);
        for (int x=lowX; x<=highX; x++)
            for (int z=lowZ; z<=highZ; z++)
                out.add(dimension + "|" + x + "," + z);
    }
}
