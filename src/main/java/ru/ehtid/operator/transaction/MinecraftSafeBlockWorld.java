package ru.ehtid.operator.transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Experimental server-thread-only adapter for a SMALL default-state palette.
 * No block entities, waterlogging, fluids, redstone or complex states supported.
 */
public final class MinecraftSafeBlockWorld implements RecoveryExecutor.MutableWorld {
    private final ServerLevel level;

    public MinecraftSafeBlockWorld(ServerLevel level) {
        this.level = level;
    }

    public static BlockState resolve(RecoveryPlanner.State state) {
        if (state == null || !SafeBlockPalette.accepts(
                state.blockId(), state.blockState(), state.blockEntitySnbt())) return null;
        BlockState result = switch (state.blockId()) {
            case "minecraft:air" -> Blocks.AIR.defaultBlockState();
            case "minecraft:stone" -> Blocks.STONE.defaultBlockState();
            case "minecraft:cobblestone" -> Blocks.COBBLESTONE.defaultBlockState();
            case "minecraft:stone_bricks" -> Blocks.STONE_BRICKS.defaultBlockState();
            case "minecraft:oak_planks" -> Blocks.OAK_PLANKS.defaultBlockState();
            case "minecraft:spruce_planks" -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case "minecraft:birch_planks" -> Blocks.BIRCH_PLANKS.defaultBlockState();
            case "minecraft:polished_deepslate" -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case "minecraft:bricks" -> Blocks.BRICKS.defaultBlockState();
            case "minecraft:glass" -> Blocks.GLASS.defaultBlockState();
            default -> null;
        };
        if (result == null || !result.toString().equals(state.blockState())) return null;
        return result;
    }

    @Override
    public RecoveryPlanner.State read(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.hasChunkAt(pos)) return null;
        BlockState actual = level.getBlockState(pos);
        if (level.getBlockEntity(pos) != null) {
            // Do not serialize or expose container NBT through a world-write adapter.
            return new RecoveryPlanner.State(
                    BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString(),
                    actual.toString(), "UNSUPPORTED_BLOCK_ENTITY");
        }
        return new RecoveryPlanner.State(
                BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString(),
                actual.toString(), null
        );
    }

    @Override
    public boolean compareAndRestore(RecoveryPlanner.Position position,
                                     RecoveryPlanner.State expected,
                                     RecoveryPlanner.State restore) {
        if (resolve(expected) == null || resolve(restore) == null) return false;
        RecoveryPlanner.State current = read(position.x(), position.y(), position.z());
        if (current == null || !current.equals(expected)) return false;
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        // Flag 2 synchronizes client changes without arbitrary neighbor updates.
        // This prototype excludes neighbor-dependent / ticking blocks.
        if (!level.setBlock(pos, resolve(restore), 2)) return false;
        return restore.equals(read(position.x(), position.y(), position.z()));
    }
}
