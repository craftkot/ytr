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
    private static final BlockPos SOURCE2 = SOURCE.offset(0, 0, 1);
    private static final BlockPos TARGET2 = TARGET.offset(0, 0, 1);
    private static final String ROOT = "operator_reality";
    private int phase;
    private long phaseStart;
    private String ownerId = "";
    private int repeatRevision=2;
    private boolean repeatAway,repeatViewActive;
    private int dynamicPivotPhase;
    private int voxelTransactionPhase;
    private java.util.UUID voxelJournalId;
    private static final String VOXEL_WORLD_KEY="minecraft:overworld";
    private static final ru.ehtid.operator.reality.SectionTerrainMesh.Key VOXEL_SECTION=
            new ru.ehtid.operator.reality.SectionTerrainMesh.Key(-2,12,-2);

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
                    // Turn 180 degrees in place first. The client extraction
                    // hook must select the prepared revision on the first
                    // hidden frame, independently of the server tick rate.
                    placeCamera(player, 4.5D, 0.0F);
                    phase = 6;
                    phaseStart = tick;
                    OperatorMod.LOGGER.info("OPERATOR_FRAME_REALITY_TEST TURN_IN_PLACE");
                }
                case 6 -> {
                    // Allow slow software-rendered CI frames to be drawn.
                    if (tick - phaseStart < 40) return;
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
                    // Explicit CI-only stone/air physical transaction.
                    // The player is teleported 100+ blocks away BEFORE the
                    // server touches the candidate section. This is never
                    // an automatic player-world gameplay mutation.
                    if(voxelTransactionPhase==0 && Files.isRegularFile(
                            directory.resolve("ci-voxel-world-apply.allow"))) {
                        placeCamera(player,128.5D,0.0F);
                        var access=newVoxelAccess(level,player);
                        var before=access.snapshot(VOXEL_SECTION);
                        var after=before.clone();
                        for(int localX=7;localX<=9;localX++) {
                            int idx=ru.ehtid.operator.reality.VoxelSectionSurface.index(localX,10,8);
                            if(before[idx]!=ru.ehtid.operator.reality.VoxelSectionSurface.STONE)
                                throw new IllegalStateException("CI voxel surface changed before test");
                            after[idx]=ru.ehtid.operator.reality.VoxelSectionSurface.AIR;
                        }
                        var plan=ru.ehtid.operator.reality.VoxelSectionTransition.prepare(
                                1,before,after,access.halo(VOXEL_SECTION));
                        voxelJournalId=java.util.UUID.randomUUID();
                        var journalDir=directory.resolve("voxel-wal");
                        var prepared=ru.ehtid.operator.reality.VoxelWriteAheadLog.prepare(
                                voxelJournalId,VOXEL_WORLD_KEY,VOXEL_SECTION,plan);
                        // This fully fsynced intent is required BEFORE setBlock.
                        ru.ehtid.operator.reality.VoxelWriteAheadLog.create(journalDir,prepared);
                        OperatorMod.LOGGER.info(
                                "OPERATOR_VOXEL_WAL_PREPARED section=-2,12,-2 changed={} phase=PREPARED",
                                prepared.changedCount());
                        var result=ru.ehtid.operator.reality.VoxelWorldTransaction.apply(
                                access,VOXEL_SECTION,plan);
                        if(result.status()!=ru.ehtid.operator.reality.VoxelWorldTransaction.Status.APPLIED)
                            throw new IllegalStateException("Voxel commit "+result.status());
                        ru.ehtid.operator.reality.VoxelWriteAheadLog.advance(
                                journalDir,prepared,ru.ehtid.operator.reality.VoxelWriteAheadLog.Phase.APPLIED);
                        voxelTransactionPhase=1;
                        placeCamera(player,4.5D,180.0F);
                        OperatorMod.LOGGER.info(
                                "OPERATOR_VOXEL_WORLD_COMMITTED section=-2,12,-2 changed={} revision=1",
                                result.changed());
                        return;
                    }
                    if(voxelTransactionPhase==1 && Files.isRegularFile(
                            directory.resolve("ci-voxel-world-rollback.allow"))) {
                        placeCamera(player,128.5D,0.0F);
                        var access=newVoxelAccess(level,player);
                        // The exact previous in-memory plan is intentionally
                        // discarded. Recovery must reopen its checksum-verified
                        // journal and restore the real world from disk alone.
                        var result=ru.ehtid.operator.reality.VoxelJournalRecovery.recover(
                                access,directory.resolve("voxel-wal"),voxelJournalId,
                                VOXEL_WORLD_KEY,VOXEL_SECTION);
                        if(result.status()!=ru.ehtid.operator.reality.VoxelJournalRecovery.Status.RECOVERED)
                            throw new IllegalStateException("Voxel WAL recovery "+result.status());
                        voxelTransactionPhase=2;
                        OperatorMod.LOGGER.info(
                                "OPERATOR_VOXEL_WAL_RECOVERED section=-2,12,-2 restored={} phase=RESOLVED",
                                result.restored());
                        placeCamera(player,4.5D,180.0F);
                        OperatorMod.LOGGER.info(
                                "OPERATOR_VOXEL_WORLD_RESTORED section=-2,12,-2 changed={} revision=1",
                                result.restored());
                        return;
                    }
                    // The dynamic cache must keep already-uploaded regions
                    // resident through an instantaneous 180-degree pivot.
                    // Fixture opt-in only; no world mutation.
                    if(dynamicPivotPhase==0 && Files.isRegularFile(directory.resolve("ci-dynamic-pivot-away.allow"))) {
                        placeCamera(player,4.5D,0.0F);
                        dynamicPivotPhase=1;
                        OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_PIVOT_AWAY");
                        return;
                    }
                    if(dynamicPivotPhase==1 && Files.isRegularFile(directory.resolve("ci-dynamic-pivot-return.allow"))) {
                        placeCamera(player,4.5D,180.0F);
                        dynamicPivotPhase=2;
                        OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_PIVOT_RETURN");
                        return;
                    }
                    // Repeated in-place camera turns are held until the
                    // actual client acknowledges the hidden extraction frame.
                    // No additional server block changes during these turns.
                    if (Files.isRegularFile(directory.resolve("ci-gpu-repeat.allow"))) {
                        if (!repeatViewActive) {
                            // An elevated test vantage sees the changing top faces,
                            // instead of a single opaque wall filling the tunnel.
                            level.setBlock(new BlockPos(0,223,10),Blocks.STONE.defaultBlockState(),2);
                            placeRepeatCamera(player,180.0F);
                            repeatViewActive=true;
                            return;
                        }
                        if (repeatRevision<=4) {
                            if (!repeatAway && Files.isRegularFile(directory.resolve("ci-repeat-away-"+repeatRevision+".allow"))) {
                                placeRepeatCamera(player,0.0F);
                                repeatAway=true;
                                OperatorMod.LOGGER.info("OPERATOR_REPEAT_CAMERA_AWAY revision={}",repeatRevision);
                            } else if (repeatAway && Files.isRegularFile(directory.resolve("ci-repeat-return-"+repeatRevision+".allow"))) {
                                placeRepeatCamera(player,180.0F);
                                repeatAway=false;
                                OperatorMod.LOGGER.info("OPERATOR_REPEAT_CAMERA_RETURN revision={}",repeatRevision);
                                repeatRevision++;
                            }
                        }
                        return;
                    }
                    if(repeatViewActive) {
                        placeCamera(player,4.5D,180.0F);
                        repeatViewActive=false;
                        OperatorMod.LOGGER.info("OPERATOR_REPEAT_VIEW_RESTORED");
                    }
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
                || !TunnelFoldService.MARKER_V2.equals(journal.sourceSnapshotId)
                || !"COMMITTED".equals(wal.phase) || wal.appliedWrites != 4)
            return "JOURNAL_OR_WAL_INVALID";
        return "COMMITTED";
    }

    private static boolean isOriginal(ServerLevel level) {
        return level.getBlockState(SOURCE).is(Blocks.STONE)
                && level.getBlockState(SOURCE2).is(Blocks.STONE)
                && level.getBlockState(TARGET).isAir()
                && level.getBlockState(TARGET2).isAir();
    }

    private static boolean isFolded(ServerLevel level) {
        return level.getBlockState(SOURCE).isAir()
                && level.getBlockState(SOURCE2).isAir()
                && level.getBlockState(TARGET).is(Blocks.STONE)
                && level.getBlockState(TARGET2).is(Blocks.STONE);
    }

    private static ru.ehtid.operator.reality.ServerVoxelWorldAccess newVoxelAccess(
            ServerLevel level,ServerPlayer player) {
        return new ru.ehtid.operator.reality.ServerVoxelWorldAccess(level,
                ()->!new net.minecraft.world.phys.AABB(-40,184,-40,-8,216,-8)
                        .contains(player.position()) && player.level()==level);
    }

    private static void placeRepeatCamera(ServerPlayer player,float yaw) {
        boolean moved=player.teleportTo((ServerLevel)player.level(),
                .5D,224D,10.5D,Set.of(),yaw,35F,true);
        if(!moved)throw new IllegalStateException("Repeat camera teleport rejected");
    }

    private static void placeCamera(ServerPlayer player, double z, float yaw) {
        // ServerPlayer#setYRot only changes server state; the Minecraft camera
        // continues looking at yaw=0 (south) unless yaw/pitch are sent as part
        // of the actual teleport packet to the connected client.
        // This is an absolute full teleport, not relative movement.
        boolean moved = player.teleportTo((ServerLevel) player.level(),
                0.5D, Y, z, Set.of(), yaw, -5.0F, true);
        if (!moved) throw new IllegalStateException("rotation-aware teleport rejected");
    }

    private static void buildCameraScene(ServerLevel level) {
        // CI-only surface fixture: seed terrain alone can lie well below
        // this elevated camera, preventing both GPU tiles from ever entering
        // the frustum. Build two real stone surfaces north of the tunnel,
        // with a shared continuous edge and room for the +/-4 height variants.
        // They are deliberately outside the physical fold's safety halo.
        for (int x=-32; x<32; x++)
            for (int z=-32; z<-8; z++) {
                int top=Y+2+(Math.floorDiv(-z-8,8)%2);
                level.setBlock(new BlockPos(x,top-1,z),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(new BlockPos(x,top,z),Blocks.STONE.defaultBlockState(),2);
            }
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
        level.setBlock(SOURCE2, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(TARGET, Blocks.AIR.defaultBlockState(), 2);
        level.setBlock(TARGET2, Blocks.AIR.defaultBlockState(), 2);
        if (!isOriginal(level)) throw new IllegalStateException("scene did not initialize");
    }

    private void failure(String reason) {
        phase = 99;
        OperatorMod.LOGGER.error("OPERATOR_CLIENT_TEST FAILED {}", reason);
    }
}
