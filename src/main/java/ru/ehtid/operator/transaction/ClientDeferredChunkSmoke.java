package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.observe.ObservationEngine;
import ru.ehtid.operator.storage.OperatorStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;
import ru.ehtid.operator.storage.RelocationExecutionStorage;

/**
 * CI only. A true GUI client and a real integrated Minecraft server.
 * Stage a future boundary intent at x=255/256 while both chunks are absent;
 * only AFTER the intent is durable, load both chunks into a disposable world,
 * and let ordinary ObservationEngine ticks apply/rollback the cross-border WAL.
 */
public final class ClientDeferredChunkSmoke {
    private static final int Y = 200;
    private static final BlockPos SOURCE = new BlockPos(256,202,0);
    private static final BlockPos SOURCE2 = SOURCE.offset(0,0,1);
    private static final BlockPos TARGET = new BlockPos(255,202,0);
    private static final BlockPos TARGET2 = TARGET.offset(0,0,1);
    private int phase;
    private long startTick;

    public void tick(MinecraftServer server, ObservationEngine observer) {
        if (!Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()) return;
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("operator_reality");
        if (!Files.isRegularFile(root.resolve("ci-client.allow"))
                || !Files.isRegularFile(root.resolve("ci-deferred-start.allow"))) return;
        List<ServerPlayer> users = server.getPlayerList().getPlayers();
        if (users.size() != 1 || users.get(0).isSpectator()) return;
        ServerPlayer player = users.get(0);
        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();
        try {
            switch (phase) {
                case 0 -> {
                    // Earlier graphical field and rollback must be complete.
                    if (!Files.isRegularFile(root.resolve("ci-world-field-restored-captured.allow")))
                        return;
                    teleport(player, 0.5D, 128.5D, 0.0F);
                    String result = observer.ciStageDeferredBoundary(
                            server, player, TARGET.getX(), TARGET.getY(), TARGET.getZ());
                    if (!"CI_DEFERRED_STAGED".equals(result)) {
                        fail("STAGE_" + result); return;
                    }
                    var data = OperatorStorage.load(server);
                    var plan = data.worldFieldPending.get(
                            WorldFieldPolicy.sector(level.dimension().toString(),
                                    TARGET.getX(), TARGET.getZ()));
                    if (plan == null || !plan.valid() || level.hasChunkAt(TARGET)
                            || level.hasChunkAt(SOURCE)) {
                        fail("INTENT_NOT_PERSISTED_BEFORE_CHUNK_LOAD"); return;
                    }
                    phase = 1;
                    startTick = now;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_DEFERRED_TEST PERSISTED_UNLOADED targetChunk=(15,0) sourceChunk=(16,0) plan={}",
                            plan.planId);
                }
                case 1 -> {
                    if (!Files.isRegularFile(root.resolve("ci-deferred-load.allow"))) return;
                    // Only the isolated test preloads chunks, after persistence.
                    // The real production scheduler never calls getChunk.
                    for (int cx = 15; cx <= 16; cx++)
                        for (int cz = -1; cz <= 8; cz++)
                            level.getChunk(cx,cz);
                    buildScene(level);
                    if (!original(level)) { fail("INITIAL_GEOMETRY_INVALID"); return; }
                    teleport(player, 255.5D, 128.5D, 0.0F);
                    phase = 2;
                    startTick = now;
                    OperatorMod.LOGGER.info("OPERATOR_DEFERRED_TEST TWO_CHUNKS_LOADED_AFTER_PLAN");
                }
                case 2 -> {
                    if (!folded(level)) {
                        if (now - startTick > 1900L) fail("COMMIT_TIMEOUT");
                        return;
                    }
                    var saved = OperatorStorage.load(server);
                    String sector = WorldFieldPolicy.sector(
                            level.dimension().toString(), TARGET.getX(), TARGET.getZ());
                    String ref = saved.worldFieldHistory.get(sector);
                    String prefix = player.getUUID().toString() + "/";
                    if (ref == null || !ref.startsWith(prefix)
                            || saved.worldFieldPending.containsKey(sector)) {
                        fail("PENDING_NOT_RESOLVED_TO_DURABLE_HISTORY"); return;
                    }
                    var journal = TransactionJournalStorage.load(
                            server, player.getUUID().toString(), ref.substring(prefix.length()));
                    var wal = RelocationExecutionStorage.load(server, journal);
                    if (!WorldFieldService.MARKER.equals(journal.sourceSnapshotId)
                            || !ChunkBoundaryPolicy.spansExactlyTwoChunks(journal)
                            || !SandboxTransactionEngine.isTunnelFold(journal)
                            || !"COMMITTED".equals(wal.phase) || wal.appliedWrites != 4
                            || !WorldFieldService.historyMatchesDisk(server,saved.worldFieldHistory)) {
                        fail("CROSS_CHUNK_WAL_OR_HISTORY_INVALID"); return;
                    }
                    teleport(player, 255.5D, 4.5D, 180.0F);
                    phase = 3;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_DEFERRED_TEST COMMITTED twoChunks=true fourWalWrites=4");
                }
                case 3 -> {
                    if (!Files.isRegularFile(root.resolve("ci-deferred-after-captured.allow"))) return;
                    teleport(player, 255.5D, 128.5D, 0.0F);
                    String result = observer.rollbackWorldField(player, server);
                    if (!result.startsWith("Tunnel fold rollback ROLLED_BACK")
                            || !original(level)) {
                        fail("ROLLBACK_" + result); return;
                    }
                    teleport(player, 255.5D, 4.5D, 180.0F);
                    phase = 4;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_DEFERRED_TEST ROLLBACK_COMPLETE bothChunksRestored=true");
                }
                case 4 -> {
                    if (!Files.isRegularFile(root.resolve("ci-deferred-restored-captured.allow")))
                        return;
                    if (!original(level)) { fail("RESTORED_GEOMETRY_INVALID"); return; }
                    phase = 5;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_DEFERRED_TEST SUCCESS persistedIntentBeforeLoad=true twoChunks=true");
                }
                default -> { }
            }
        } catch (IOException | RuntimeException exception) {
            fail(exception.getClass().getSimpleName() + "_" + exception.getMessage());
        }
    }

    private static void buildScene(ServerLevel level) {
        // The same camera composition as the first tunnel, but straddling
        // x=255/256 so the two-cell source and target cross the chunk edge.
        for (int x = 252; x <= 259; x++)
            for (int z = -5; z <= 25; z++)
                for (int y = Y - 1; y <= Y + 13; y++)
                    level.setBlock(new BlockPos(x,y,z), Blocks.AIR.defaultBlockState(), 2);
        for (int x = 253; x <= 258; x++)
            for (int z = -4; z <= 24; z++) {
                level.setBlock(new BlockPos(x,Y-1,z),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(new BlockPos(x,Y+12,z),Blocks.STONE.defaultBlockState(),2);
            }
        for (int z = -4; z <= 24; z++)
            for (int y = Y; y <= Y + 5; y++) {
                level.setBlock(new BlockPos(252,y,z),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(new BlockPos(259,y,z),Blocks.STONE.defaultBlockState(),2);
            }
        for (int z : new int[]{-4,4,12,20})
            for (int x : new int[]{253,258})
                level.setBlock(new BlockPos(x,Y+4,z),Blocks.SEA_LANTERN.defaultBlockState(),2);
        level.setBlock(SOURCE,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(SOURCE2,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(TARGET,Blocks.AIR.defaultBlockState(),2);
        level.setBlock(TARGET2,Blocks.AIR.defaultBlockState(),2);
        // Opaque terrain between the remote actor and target, still loaded
        // into the disposable test world; glass would fail the production gate.
        for (int x = 252; x <= 259; x++)
            for (int y = Y; y <= Y+5; y++)
                level.setBlock(new BlockPos(x,y,40),Blocks.STONE.defaultBlockState(),2);
    }

    private static void teleport(ServerPlayer player, double x, double z, float yaw) {
        if (!player.teleportTo((ServerLevel)player.level(),
                x, Y, z, Set.of(), yaw,-5.0F,true))
            throw new IllegalStateException("client position packet rejected");
    }

    private static boolean original(ServerLevel level) {
        return level.getBlockState(SOURCE).is(Blocks.STONE)
                && level.getBlockState(SOURCE2).is(Blocks.STONE)
                && level.getBlockState(TARGET).isAir()
                && level.getBlockState(TARGET2).isAir();
    }

    private static boolean folded(ServerLevel level) {
        return level.getBlockState(SOURCE).isAir()
                && level.getBlockState(SOURCE2).isAir()
                && level.getBlockState(TARGET).is(Blocks.STONE)
                && level.getBlockState(TARGET2).is(Blocks.STONE);
    }

    private void fail(String reason) {
        phase = 99;
        OperatorMod.LOGGER.error("OPERATOR_DEFERRED_TEST FAILED {}", reason);
    }
}
