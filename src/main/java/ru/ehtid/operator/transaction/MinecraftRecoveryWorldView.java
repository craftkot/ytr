package ru.ehtid.operator.transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Live Minecraft read-only adapter.
 *
 * Never asks the server to load a chunk; inaccessible cells return null.
 * Contains NO setter and cannot be passed to RecoveryExecutor.restore().
 */
public final class MinecraftRecoveryWorldView implements RecoveryPlanner.WorldView {
    private final ServerLevel level;

    public MinecraftRecoveryWorldView(ServerLevel level) {
        if (level == null) throw new IllegalArgumentException("Server level required");
        this.level = level;
    }

    @Override
    public RecoveryPlanner.State read(int x, int y, int z) {
        BlockPos position = new BlockPos(x, y, z);
        if (!level.hasChunkAt(position)) return null;
        BlockState state = level.getBlockState(position);
        BlockEntity blockEntity = level.getBlockEntity(position);
        String nbt = blockEntity == null
                ? null : blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
        return new RecoveryPlanner.State(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                state.toString(), nbt
        );
    }
}
