package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.observe.ObservationEngine;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * CI-only in-game smoke using an ACTUAL player in a graphical integrated
 * Minecraft client. Unlike ServerTunnelFoldSmoke, no synthetic player is used.
 * A Python Xvfb screenshot runner signals frame capture via world-local files.
 *
 * Requires JVM opt-in + GitHub Actions + marker in a disposable client world.
 * NEVER activates in normal gameplay or a dedicated server.
 */
public final class ClientTunnelSmoke {
    private static final int Y = 200;
    private static final BlockPos SOURCE = new BlockPos(1, Y + 2, 0);
    private static final BlockPos TARGET = new BlockPos(0, Y + 2, 0);
    private static final String ROOT = "operator_reality";
    private int phase;
    private long phaseStart;
    private String ownerId = "";

    public void tick(MinecraftServer server, ObservationEngine observer) {
        if (!Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()) return;
        Path directory = server.getWorldPath(LevelResource.ROOT).resolve(ROOT);
        if (!Files.isRegularFile(directory.resolve("ci-client.allow"))) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.size() != 1 || players.get(0).isSpectator()) return;

        ServerPlayer player = players.get(0);
        ServerLevel level = (ServerLevel) player.level();
        long tick = level.getGameTime();
        try {
            switch (phase) {
                case 0 -> {
                    if (!level.dimension().equals(server.overworld().dimension())) return;
                    buildCameraScene(level);
                    placeCamera(player, 4.5D, 180.0F);
                    ownerId = player.getUUID().toString();
                    phase = 1;
                    phaseStart = tick;
                    OperatorMod.LOGGER.info("OPERATOR_CLIENT_TEST SCENE_READY actor={} source={} target={}",
                            ownerId, SOURCE, TARGET);
                }
                case 1 -> {
                    // Wait for an actual frame captured from the Minecraft window.
                    if (tick - phaseStart < 100
                            || !Files.isRegularFile(directory.resolve("ci-before-captured.allow"))) return;
                    // Real connected actor is moved behind the mapped tunnel and
                    // made to face away to satisfy the same TUNNEL_FOLD planner.
                    placeCamera(player, 22.5D, 0.0F);
                    String result = observer.ciPrimeRealClientPlayer(server, player, 0, Y, 0);
                    if (!"CI_PRIMED".equals(result)) {
                        failure("PRIME_" + result);
                        return;
                    }
                    phase = 2;
                    phaseStart = tick;
                    OperatorMod.LOGGER.info("OPERATOR_CLIENT_TEST ARMED connectedPlayer={} personalTicks=24000",
                            ownerId);
                }
                case 2 -> {
                    // The normal ObservationEngine.onServerTick must perform the
                    // change on its NEXT 200-tick decision boundary.
                    if (!isFolded(level)) {
                        if (tick - phaseStart > 500) failure("AUTO_EVENT_TIMEOUT");
                        return;
                    }
                    String result = verifyCommitted(server, player);
                    if (!"COMMITTED".equals(result)) {
                        failure("POSTCHECK_" + result);
                        return;
                    }
                    placeCamera(player, 4.5D, 180.0F);
                    phase = 3;
                    phaseStart = tick;
                    OperatorMod.LOGGER.info("OPERATOR_CLIENT_TEST AUTONOMOUS_BLOCK_MOVED actualPlayer={} "
                            + "sourceAir=true targetStone=true wal=COMMITTED", ownerId);
                }
                case 3 -> {
                    if (!Files.isRegularFile(directory.resolve("ci-after-captured.allow"))) return;
                    // Player must move away from the target before rollback.
                    placeCamera(player, 22.5D, 0.0F);
                    String journalId = observer.ciFoldJournalId(player);
                    if (journalId.isEmpty()) {
                        failure("NO_SAVED_JOURNAL");
                        return;
                    }
                    String result = TunnelFoldService.rollback(server, player, journalId);
                    if (!result.startsWith("Tunnel fold rollback ROLLED_BACK")
                            || !isOriginal(level)) {
                        failure("ROLLBACK_" + result);
                        return;
                    }
                    placeCamera(player, 4.5D, 180.0F);
                    phase = 4;
                    phaseStart = tick;
                    OperatorMod.LOGGER.info("OPERATOR_CLIENT_TEST ROLLBACK_COMPLETE realBlocksRestored=true");
                }
                case 4 -> {
                    if (!Files.isRegularFile(directory.resolve("ci-restored-captured.allow"))) return;
                    if (!isOriginal(level)) {
                        failure("ORIGINAL_BLOCKS_LOST");
                        return;
                    }
                    phase = 5;
                    OperatorMod.LOGGER.info("OPERATOR_CLIENT_TEST SUCCESS actualIntegratedPlayer="
                            + ownerId + " beforeAndAfterAndRestoredScreenshots=true");
                }
                default -> { }
            }
        } catch (IOException | RuntimeException error) {
            failure(error.getClass().getSimpleName() + "_" + error.getMessage());
        }
    }

    private static String verifyCommitted(MinecraftServer server, ServerPlayer player)
            throws IOException {
        String root = player.getUUID().toString();
        var data = ru.ehtid.operator.storage.OperatorStorage.load(server);
        var memory = data.players.get(root);
        if (memory == null || memory.autonomousFoldJournalId.isEmpty())
            return "MISSING_PLAYER_MEMORY";
        var journal = TransactionJournalStorage.load(
                server, root, memory.autonomousFoldJournalId);
        var wal = RelocationExecutionStorage.load(server, journal);
        if (!SandboxTransactionEngine.isTunnelFold(journal)
                || !"COMMITTED".equals(wal.phase) || wal.appliedWrites != 2)
            return "JOURNAL_OR_WAL_INVALID";
        return "COMMITTED";
    }

    private static boolean isOriginal(ServerLevel level) {
        return level.getBlockState(SOURCE).is(Blocks.STONE)
                && level.getBlockState(TARGET).isAir();
    }

    private static boolean isFolded(ServerLevel level) {
        return level.getBlockState(SOURCE).isAir()
                && level.getBlockState(TARGET).is(Blocks.STONE);
    }

    private static void placeCamera(ServerPlayer player, double z, float yaw) {
        // Changes go through the live ServerPlayer connection, not an offline fake.
        player.teleportTo(0.5D, Y, z);
        player.setYRot(yaw);
        player.setXRot(-5.0F);
    }

    private static void buildCameraScene(ServerLevel level) {
        // Build a real cave corridor in a disposable integrated world, with a
        // camera vantage at z=8.5 and mutation at z=0.
        for (int x = -4; x <= 4; x++)
            for (int z = -5; z <= 25; z++)
                for (int y = Y - 1; y <= Y + 13; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
        for (int x = -3; x <= 3; x++)
            for (int z = -4; z <= 24; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, Y + 12, z), Blocks.STONE.defaultBlockState(), 2);
            }
        // Stone side walls around the cavern; light sources are kept OUTSIDE
        // the transaction's one-block halo.
        for (int z = -4; z <= 24; z++)
            for (int y = Y; y <= Y + 5; y++) {
                level.setBlock(new BlockPos(-3, y, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(3, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        for (int z : new int[]{-4, 4, 12, 20})
            for (int x : new int[]{-2, 2})
                level.setBlock(new BlockPos(x, Y + 4, z),
                        Blocks.SEA_LANTERN.defaultBlockState(), 2);
        level.setBlock(SOURCE, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(TARGET, Blocks.AIR.defaultBlockState(), 2);
        if (!isOriginal(level)) throw new IllegalStateException("scene did not initialize");
    }

    private void failure(String reason) {
        phase = 99;
        OperatorMod.LOGGER.error("OPERATOR_CLIENT_TEST FAILED {}", reason);
    }
}
