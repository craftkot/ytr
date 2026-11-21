package ru.ehtid.operator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * CI-only client world observer. It reports the CLIENT'S own BlockStates,
 * not the integrated server values, so visual smoke tests cannot accidentally
 * count a server-only mutation as a rendered world change.
 *
 * No world writes, never enabled in distributed gameplay by default.
 */
public final class OperatorClientMod implements ClientModInitializer {
    private static final BlockPos SOURCE = new BlockPos(1, 202, 0);
    private static final BlockPos TARGET = new BlockPos(0, 202, 0);
    private static final BlockPos DEFERRED_SOURCE = new BlockPos(256,202,0);
    private static final BlockPos DEFERRED_TARGET = new BlockPos(255,202,0);
    private long lastSample;
    private String previous = "";
    private String previousDeferred = "";

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;
            long tick = client.level.getGameTime();
            String source = client.level.getBlockState(SOURCE).is(Blocks.STONE)
                    ? "STONE" : client.level.getBlockState(SOURCE).isAir() ? "AIR" : "OTHER";
            String target = client.level.getBlockState(TARGET).is(Blocks.STONE)
                    ? "STONE" : client.level.getBlockState(TARGET).isAir() ? "AIR" : "OTHER";
            String snapshot = source + "/" + target;
            if (!snapshot.equals(previous) || tick - lastSample > 35L) {
                OperatorMod.LOGGER.info(
                        "OPERATOR_CLIENT_RENDER_STATE source={} target={} "
                        + "camera=({},{},{}) yaw={} pitch={} tick={}",
                        source, target,
                        (int) Math.floor(client.player.getX()),
                        (int) Math.floor(client.player.getY()),
                        (int) Math.floor(client.player.getZ()),
                        client.player.getYRot(), client.player.getXRot(), tick);
                previous = snapshot;
                lastSample = tick;
            }
            // Second site is exactly on the x=255/256 chunk boundary.
            // Read the CLIENT'S own chunks after returning from the remote
            // location so this cannot be satisfied by server-only WAL logs.
            if (Math.abs(client.player.getX() - 255.5D) < 12D
                    && Math.abs(client.player.getZ()) < 30D
                    && client.level.hasChunkAt(DEFERRED_SOURCE)
                    && client.level.hasChunkAt(DEFERRED_TARGET)) {
                String dSource = client.level.getBlockState(DEFERRED_SOURCE).is(Blocks.STONE)
                        ? "STONE" : client.level.getBlockState(DEFERRED_SOURCE).isAir() ? "AIR" : "OTHER";
                String dTarget = client.level.getBlockState(DEFERRED_TARGET).is(Blocks.STONE)
                        ? "STONE" : client.level.getBlockState(DEFERRED_TARGET).isAir() ? "AIR" : "OTHER";
                String state = dSource + "/" + dTarget;
                if (!state.equals(previousDeferred)) {
                    OperatorMod.LOGGER.info(
                            "OPERATOR_CLIENT_DEFERRED_RENDER_STATE source={} target={} yaw={} tick={}",
                            dSource, dTarget, client.player.getYRot(), tick);
                    previousDeferred = state;
                }
            }
        });
    }
}
