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
 * Real, connected-client integration for the distributed world field.
 * Activated by the Python screenshot harness only AFTER the ordinary
 * tunnel test has finished in the same disposable world. Tests actual
 * offscreen mutation, journal index and the later return/rollback images.
 */
public final class ClientWorldFieldSmoke {
    private static final int Y = 200;
    private static final BlockPos SOURCE = new BlockPos(1, 202, 0);
    private static final BlockPos TARGET = new BlockPos(0, 202, 0);
    private static final BlockPos SOURCE2 = SOURCE.offset(0, 0, 1);
    private static final BlockPos TARGET2 = TARGET.offset(0, 0, 1);
    private int phase;
    private long started;

    public void tick(MinecraftServer server, ObservationEngine observer) {
        if (!Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()) return;
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("operator_reality");
        if (!Files.isRegularFile(root.resolve("ci-client.allow"))
                || !Files.isRegularFile(root.resolve("ci-world-field-start.allow"))) return;
        List<ServerPlayer> connected = server.getPlayerList().getPlayers();
        if (connected.size() != 1 || connected.get(0).isSpectator()) return;
        ServerPlayer player = connected.get(0);
        ServerLevel level = (ServerLevel) player.level();
        long now = level.getGameTime();
        try {
            switch (phase) {
                case 0 -> {
                    if (!original(level)) { fail("BASELINE_NOT_RESTORED"); return; }
                    // The CI harness is allowed to preload fixture chunks.
                    // Production WorldFieldService NEVER does this.
                    level.getChunk(0, 0);
                    level.getChunk(0, 8);
                    for (int x = -3; x <= 3; x++)
                        for (int z = 124; z <= 132; z++)
                            level.setBlock(new BlockPos(x, Y - 1, z),
                                    Blocks.STONE.defaultBlockState(), 2);
                    // Production's line-of-sight veto must see this real
                    // opaque barrier between remote player and the cave.
                    // It is behind the camera when revisiting the tunnel.
                    for (int x = -3; x <= 3; x++)
                        for (int y = Y; y <= Y + 5; y++)
                            level.setBlock(new BlockPos(x, y, 40),
                                    Blocks.STONE.defaultBlockState(), 2);
                    place(player, 128.5D, 0.0F);
                    String primed = observer.ciPrimeWorldField(server, player);
                    if (!"CI_FIELD_PRIMED".equals(primed)) { fail("PRIME_" + primed); return; }
                    phase = 1;
                    started = now;
                    OperatorMod.LOGGER.info("OPERATOR_WORLD_FIELD_TEST REMOTE_READY actor={} cameraZ=128.5", player.getUUID());
                }
                case 1 -> {
                    if (!folded(level)) {
                        if (now - started > 2100L) fail("WORLD_FIELD_TIMEOUT");
                        return;
                    }
                    var memory = OperatorStorage.load(server);
                    String sector = WorldFieldPolicy.sector(level.dimension().toString(),
                            TARGET.getX(), TARGET.getZ());
                    String ref = memory.worldFieldHistory.get(sector);
                    String expectedPrefix = player.getUUID().toString() + "/";
                    if (ref == null || !ref.startsWith(expectedPrefix)) {
                        fail("MISSING_PERSISTENT_SECTOR_INDEX"); return;
                    }
                    var journal = TransactionJournalStorage.load(
                            server, player.getUUID().toString(), ref.substring(expectedPrefix.length()));
                    var wal = RelocationExecutionStorage.load(server, journal);
                    if (!WorldFieldService.MARKER.equals(journal.sourceSnapshotId)
                            || !"COMMITTED".equals(wal.phase) || wal.appliedWrites != 4
                            || !WorldFieldService.historyMatchesDisk(server, memory.worldFieldHistory)) {
                        fail("WORLD_FIELD_WAL_OR_INDEX_INVALID"); return;
                    }
                    // The client returns to the same view only after the
                    // server has completed the hidden change.
                    place(player, 4.5D, 180.0F);
                    phase = 2;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_WORLD_FIELD_TEST COMMITTED hiddenSector={} fourWalWrites=4", sector);
                }
                case 2 -> {
                    if (!Files.isRegularFile(root.resolve("ci-world-field-after-captured.allow"))) return;
                    place(player, 128.5D, 0.0F);
                    String result = observer.rollbackWorldField(player, server);
                    if (!result.startsWith("Tunnel fold rollback ROLLED_BACK") || !original(level)) {
                        fail("ROLLBACK_" + result); return;
                    }
                    place(player, 4.5D, 180.0F);
                    phase = 3;
                    OperatorMod.LOGGER.info("OPERATOR_WORLD_FIELD_TEST ROLLBACK_COMPLETE");
                }
                case 3 -> {
                    if (!Files.isRegularFile(root.resolve("ci-world-field-restored-captured.allow")))
                        return;
                    if (!original(level)) { fail("RESTORED_BLOCKS_CHANGED"); return; }
                    phase = 4;
                    OperatorMod.LOGGER.info(
                            "OPERATOR_WORLD_FIELD_TEST SUCCESS offlineMutationAndReturnScreenshots=true");
                }
                default -> { }
            }
        } catch (IOException | RuntimeException error) {
            fail(error.getClass().getSimpleName() + "_" + error.getMessage());
        }
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
    private static void place(ServerPlayer player, double z, float yaw) {
        if (!player.teleportTo((ServerLevel) player.level(),
                0.5D, Y, z, Set.of(), yaw, -5.0F, true))
            throw new IllegalStateException("connected client teleport failed");
    }
    private void fail(String error) {
        phase = 99;
        OperatorMod.LOGGER.error("OPERATOR_WORLD_FIELD_TEST FAILED {}", error);
    }
}
