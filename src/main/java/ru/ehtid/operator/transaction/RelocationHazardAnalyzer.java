package ru.ehtid.operator.transaction;

import java.util.Set;

/**
 * Conservative classification of tracked block types and states.
 * A zero-hazard result is NOT permission to relocate a building.
 */
public final class RelocationHazardAnalyzer {
    private RelocationHazardAnalyzer() { }

    public record Risks(int cells, int blockEntities, int fluids,
                        int gravityBlocks, int redstoneDevices,
                        int attachedOrMultipart, int moddedBlocks,
                        int otherUnknown) {
        public boolean requiresManualReview() {
            return blockEntities > 0 || fluids > 0 || gravityBlocks > 0
                    || redstoneDevices > 0 || attachedOrMultipart > 0
                    || moddedBlocks > 0 || otherUnknown > 0;
        }

        public String summary() {
            return "entities=" + blockEntities + ",fluids=" + fluids
                    + ",gravity=" + gravityBlocks + ",redstone=" + redstoneDevices
                    + ",attachments=" + attachedOrMultipart
                    + ",modded=" + moddedBlocks + ",unknown=" + otherUnknown;
        }
    }

    public static Risks inspect(TransactionJournal journal) {
        if (!TransactionIntegrity.valid(journal)) {
            throw new IllegalArgumentException("A verified transaction is required");
        }
        int blockEntities = 0, fluids = 0, gravity = 0, redstone = 0;
        int attachments = 0, modded = 0, unknown = 0, total = 0;
        for (TransactionJournal.Cell cell : TransactionJournal.reversePreimages(journal)) {
            total++;
            if (cell.blockEntitySnbt() != null) blockEntities++;
            Category category = classify(cell.blockId(), cell.blockState());
            switch (category) {
                case FLUID -> fluids++;
                case GRAVITY -> gravity++;
                case REDSTONE -> redstone++;
                case ATTACHED -> attachments++;
                case MODDED -> modded++;
                case UNKNOWN -> unknown++;
                case ORDINARY -> { }
            }
        }
        return new Risks(total, blockEntities, fluids, gravity, redstone,
                attachments, modded, unknown);
    }

    public enum Category { ORDINARY, FLUID, GRAVITY, REDSTONE, ATTACHED, MODDED, UNKNOWN }

    private static final Set<String> GRAVITY = Set.of(
            "sand", "red_sand", "gravel", "suspicious_sand",
            "suspicious_gravel", "anvil", "chipped_anvil",
            "damaged_anvil", "dragon_egg"
    );
    private static final Set<String> REDSTONE = Set.of(
            "redstone_wire", "redstone_block", "redstone_torch",
            "redstone_wall_torch", "repeater", "comparator",
            "observer", "piston", "sticky_piston", "piston_head",
            "lever", "daylight_detector", "target", "dispenser",
            "dropper", "hopper", "note_block", "sculk_sensor",
            "calibrated_sculk_sensor", "tripwire", "tripwire_hook",
            "detector_rail", "powered_rail", "activator_rail"
    );

    public static Category classify(String blockId, String blockState) {
        if (blockId == null || blockId.isBlank()) return Category.UNKNOWN;
        int colon = blockId.indexOf(':');
        if (colon < 1 || colon == blockId.length() - 1) return Category.UNKNOWN;
        String namespace = blockId.substring(0, colon);
        if (!"minecraft".equals(namespace)) return Category.MODDED;
        String path = blockId.substring(colon + 1);
        if (blockState != null && blockState.contains("waterlogged=true"))
            return Category.FLUID;
        if (SafeBlockPalette.accepts(blockId, blockState, null))
            return Category.ORDINARY;
        if (path.equals("air") || path.equals("cave_air")
                || path.equals("void_air") || path.equals("stone")
                || path.equals("dirt") || path.equals("grass_block")
                || path.equals("cobblestone") || path.endsWith("_planks")
                || path.endsWith("_bricks")) return Category.ORDINARY;
        if (path.equals("water") || path.equals("lava")
                || path.equals("bubble_column") || path.equals("powder_snow")
                || path.equals("ice") || path.equals("frosted_ice")
                || (blockState != null && blockState.contains("waterlogged=true")))
            return Category.FLUID;
        if (GRAVITY.contains(path) || path.endsWith("_concrete_powder"))
            return Category.GRAVITY;
        if (REDSTONE.contains(path) || path.endsWith("_button")
                || path.endsWith("_pressure_plate") || path.endsWith("_rail"))
            return Category.REDSTONE;
        if (path.endsWith("_door") || path.endsWith("_bed")
                || path.endsWith("_trapdoor") || path.endsWith("_torch")
                || path.endsWith("_wall_sign") || path.endsWith("_hanging_sign")
                || path.endsWith("_fence_gate")
                || path.endsWith("_fence") || path.endsWith("_wall")
                || path.endsWith("_stairs") || path.endsWith("_slab")
                || path.endsWith("_glass_pane") || path.equals("glass_pane")
                || path.equals("ladder") || path.equals("lantern")
                || path.equals("chain") || path.equals("scaffolding")
                || path.equals("vine") || path.equals("pointed_dripstone")
                || path.equals("rail") || path.equals("fire") || path.equals("soul_fire"))
            return Category.ATTACHED;
        // Any unclassified vanilla block is conservatively UNKNOWN.
        return Category.UNKNOWN;
    }
}
