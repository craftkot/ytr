package ru.ehtid.operator.transaction;

import java.util.Map;
import java.util.Set;

/**
 * Tiny, conservative palette for the first opt-in Minecraft world-write prototype.
 * No block entities, fluids, falling blocks, neighbors-dependent or ticking blocks.
 * Only DEFAULT block states may be moved (checked again against live registry states).
 */
public final class SafeBlockPalette {
    private static final Set<String> IDS = Set.of(
            "minecraft:air",
            "minecraft:stone",
            "minecraft:cobblestone",
            "minecraft:stone_bricks",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:deepslate",
            "minecraft:polished_deepslate",
            "minecraft:bricks",
            "minecraft:glass"
    );

    private SafeBlockPalette() { }

    public static boolean accepts(String blockId, String blockState, String nbt) {
        return nbt == null && blockId != null && IDS.contains(blockId)
                && blockState != null && blockState.equals("Block{" + blockId + "}");
    }

    public static boolean accepts(TransactionJournal journal) {
        if (!TransactionIntegrity.valid(journal)) return false;
        for (TransactionJournal.Cell cell : TransactionJournal.reversePreimages(journal)) {
            if (!accepts(cell.blockId(), cell.blockState(), cell.blockEntitySnbt())) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOrdinaryAir(String blockId) {
        return "minecraft:air".equals(blockId);
    }
}
