package ru.ehtid.operator.transaction;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Checks a 1-block halo around both region AABBs without loading chunks.
 * This is a hazard report, not a navigation, physics or redstone simulation.
 */
public final class RecoveryBoundaryInspector {
    public static final int MAX_NEIGHBOR_SAMPLES = 8192;

    private RecoveryBoundaryInspector() { }

    public record Assessment(String status, int sampled, int unavailable,
                             int blockEntities, int mechanisms,
                             int fluidBlocks, int attachedBlocks,
                             int moddedBlocks, int otherUnknown) {
        public String summary() {
            return status + " | neighbor cells: " + sampled
                    + " | inaccessible: " + unavailable
                    + " | block entities: " + blockEntities
                    + " | mechanisms: " + mechanisms
                    + " | fluids: " + fluidBlocks
                    + " | attachments: " + attachedBlocks
                    + " | modded: " + moddedBlocks
                    + " | unknown: " + otherUnknown;
        }
    }

    public static Assessment inspect(ServerLevel level, TransactionJournal journal) {
        if (level == null || !TransactionIntegrity.valid(journal)) {
            return new Assessment("INVALID_JOURNAL", 0, 0, 0, 0, 0, 0, 0, 0);
        }
        long upper = haloSize(journal.source) + haloSize(journal.target);
        if (upper < 0 || upper > MAX_NEIGHBOR_SAMPLES) {
            return new Assessment("SCAN_BUDGET_EXCEEDED", 0, 0, 0, 0, 0, 0, 0, 0);
        }

        Set<Pos> visited = new HashSet<>();
        int unavailable = 0, entities = 0, mechanisms = 0, fluids = 0;
        int attached = 0, modded = 0, unknown = 0;

        for (TransactionJournal.Region region : new TransactionJournal.Region[]{
                journal.source, journal.target
        }) {
            for (long x = (long) region.minX - 1L; x <= (long) region.maxX + 1L; x++) {
                for (long y = (long) region.minY - 1L; y <= (long) region.maxY + 1L; y++) {
                    for (long z = (long) region.minZ - 1L; z <= (long) region.maxZ + 1L; z++) {
                        if (x >= region.minX && x <= region.maxX
                                && y >= region.minY && y <= region.maxY
                                && z >= region.minZ && z <= region.maxZ) continue;
                        if (x < -30_000_000L || x >= 30_000_000L
                                || z < -30_000_000L || z >= 30_000_000L
                                || y < level.getMinY() || y >= level.getMaxY()) {
                            unavailable++;
                            continue;
                        }
                        Pos posKey = new Pos((int) x, (int) y, (int) z);
                        if (!visited.add(posKey)) continue;
                        BlockPos pos = new BlockPos(posKey.x(), posKey.y(), posKey.z());
                        if (!level.hasChunkAt(pos)) {
                            unavailable++;
                            continue;
                        }
                        BlockState state = level.getBlockState(pos);
                        if (level.getBlockEntity(pos) != null) entities++;
                        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        var category = RelocationHazardAnalyzer.classify(id, state.toString());
                        switch (category) {
                            case REDSTONE -> mechanisms++;
                            case FLUID -> fluids++;
                            case ATTACHED, GRAVITY -> attached++;
                            case MODDED -> modded++;
                            case UNKNOWN -> unknown++;
                            case ORDINARY -> { }
                        }
                    }
                }
            }
        }
        String status = unavailable > 0 ? "INCOMPLETE"
                : (entities > 0 || mechanisms > 0 || fluids > 0
                        || attached > 0 || modded > 0 || unknown > 0)
                        ? "REVIEW_REQUIRED" : "NO_LISTED_HAZARDS";
        return new Assessment(status, visited.size(), unavailable,
                entities, mechanisms, fluids, attached, modded, unknown);
    }

    private static long haloSize(TransactionJournal.Region region) {
        if (region.volume() <= 0 || region.volume() > TransactionJournal.MAX_CELLS_PER_REGION)
            return -1;
        long sx = (long) region.maxX - region.minX + 1L;
        long sy = (long) region.maxY - region.minY + 1L;
        long sz = (long) region.maxZ - region.minZ + 1L;
        return (sx + 2L) * (sy + 2L) * (sz + 2L) - region.volume();
    }

    private record Pos(int x, int y, int z) { }
}
