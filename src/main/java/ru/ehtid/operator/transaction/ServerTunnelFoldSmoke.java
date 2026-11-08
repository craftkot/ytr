package ru.ehtid.operator.transaction;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.observe.ObservationEngine;
import ru.ehtid.operator.storage.OperatorStorage;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.RelocationWatchdogStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Real dedicated Minecraft world smoke, accelerated observation like
 * BLACK FREQUENCY's isolated world integration. The actor is a synthetic
 * ServerPlayer (no network client); real chunks, transactions and persisted
 * WALs are used. The three phases require separate JVM boots.
 */
public final class ServerTunnelFoldSmoke {
    private static final UUID OWNER = UUID.fromString("e8d1d74a-e4b8-4200-9dc6-d6a40bc60f33");
    private static final int Y = 200;
    private static final BlockPos SOURCE = new BlockPos(1, Y + 2, 0);
    private static final BlockPos TARGET = new BlockPos(0, Y + 2, 0);
    private static final BlockPos SOURCE2 = SOURCE.offset(0, 0, 1);
    private static final BlockPos TARGET2 = TARGET.offset(0, 0, 1);

    private ServerTunnelFoldSmoke() { }

    public static String phase(MinecraftServer server, ObservationEngine observer, int phase) {
        if (!Boolean.getBoolean("operator.reality.ciTunnelFold")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !server.getPlayerList().getPlayers().isEmpty()
                || !Files.isRegularFile(server.getWorldPath(LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-tunnel-fold.allow"))) {
            return "OPERATOR_TUNNEL_CI_DENIED: missing CI flag/marker or connected players";
        }
        try {
            ServerLevel level = server.overworld();
            if (level.getMinY() > Y - 1 || level.getMaxY() < Y + 14)
                return "OPERATOR_TUNNEL_CI_FAILED: world height cannot fit fixture";
            // The production service never loads chunks; this isolated test
            // explicitly preloads fixture and neighbor chunks before invoking it.
            for (int cx = -1; cx <= 1; cx++)
                for (int cz = -1; cz <= 2; cz++)
                    level.getChunk(cx, cz);
            ServerPlayer actor = syntheticActor(server, level);
            String output = switch (phase) {
                case 1 -> triggerWithNegativeSafetyCases(server, level, observer, actor);
                case 2 -> verifyAfterRestartAndRestore(server, level, observer, actor);
                case 3 -> verifyRolledBackAndNoRepeat(server, level, observer, actor);
                default -> "OPERATOR_TUNNEL_CI_FAILED: unexpected phase " + phase;
            };
            if (output.contains("_OK:")) {
                System.out.println("OPERATOR_TUNNEL_TEST " + output);
            }
            return output;
        } catch (IOException | RuntimeException error) {
            return "OPERATOR_TUNNEL_CI_FAILED_PHASE" + phase + ": "
                    + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static ServerPlayer syntheticActor(MinecraftServer server, ServerLevel level) {
        ServerPlayer actor = new ServerPlayer(server, level,
                new GameProfile(OWNER, "EHTID_CI"), ClientInformation.createDefault());
        actor.setPos(0.5D, Y, 22.5D);
        actor.setYRot(0.0F); // Facing south; the chosen tunnel is north/behind.
        actor.setXRot(0.0F);
        return actor;
    }

    private static String triggerWithNegativeSafetyCases(MinecraftServer server, ServerLevel level,
                                                           ObservationEngine observer,
                                                           ServerPlayer actor)
            throws IOException {
        Path folder = server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions").resolve(OWNER.toString());
        if (Files.exists(folder))
            return "OPERATOR_TUNNEL_CI_FAILED: test world already contains transactions";
        buildTestTunnel(level);
        if (!isOriginal(level))
            return "OPERATOR_TUNNEL_CI_FAILED: stone/air fixture invalid";
        String disabled = observer.ciAttemptAutonomousFold(server, actor, true, true, 24_000L);
        if (!"DISABLED".equals(disabled))
            return "OPERATOR_TUNNEL_CI_FAILED: disabled gate returned " + disabled;
        if (!observer.setAutonomousAnomalies(server, true).contains("ENABLED"))
            return "OPERATOR_TUNNEL_CI_FAILED: could not persist opt-in";
        String untrained = observer.ciAttemptAutonomousFold(server, actor, true, false, 24_000L);
        if (!"OBSERVING".equals(untrained))
            return "OPERATOR_TUNNEL_CI_FAILED: personal observation gate " + untrained;
        String worldUntrained = observer.ciAttemptAutonomousFold(
                server, actor, false, true, 24_000L);
        if (!"OBSERVING".equals(worldUntrained))
            return "OPERATOR_TUNNEL_CI_FAILED: world observation gate " + worldUntrained;

        // A redstone block in the transaction halo must invalidate the scene.
        BlockPos hazard = TARGET.above();
        if (!level.setBlock(hazard, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2))
            return "OPERATOR_TUNNEL_CI_FAILED: cannot place hazard";
        String rejected = observer.ciAttemptAutonomousFold(
                server, actor, true, true, 24_000L);
        if (!"NO_SAFE_CANDIDATE".equals(rejected))
            return "OPERATOR_TUNNEL_CI_FAILED: hazard not rejected: " + rejected;
        if (!level.setBlock(hazard, Blocks.AIR.defaultBlockState(), 2))
            return "OPERATOR_TUNNEL_CI_FAILED: cannot remove hazard";
        if (!isOriginal(level) || Files.exists(folder))
            return "OPERATOR_TUNNEL_CI_FAILED: negative tests modified protected blocks";

        String result = observer.ciAttemptAutonomousFold(server, actor, true, true, 24_000L);
        if (!result.startsWith("Tunnel fold COMMITTED"))
            return "OPERATOR_TUNNEL_CI_FAILED: live event did not commit: " + result;
        OperatorWorldData disk = OperatorStorage.load(server);
        PlayerMemory memory = disk.players.get(OWNER.toString());
        if (memory == null || memory.autonomousFoldJournalId.isEmpty())
            return "OPERATOR_TUNNEL_CI_FAILED: one-shot memory did not persist";
        TransactionJournal journal = TransactionJournalStorage.load(
                server, OWNER.toString(), memory.autonomousFoldJournalId);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        if (!SandboxTransactionEngine.isTunnelFold(journal) || !isFolded(level)
                || !TunnelFoldService.MARKER_V2.equals(journal.sourceSnapshotId)
                || !"COMMITTED".equals(ledger.phase) || ledger.appliedWrites != 4)
            return "OPERATOR_TUNNEL_CI_FAILED: actual blocks/WAL did not match live event";
        return "OPERATOR_TUNNEL_CI_PHASE1_OK: DISABLED and OBSERVING gates passed,"
                + " hazard veto passed, actual two-stone strip relocated, WAL COMMITTED writes=4";
    }

    private static String verifyAfterRestartAndRestore(MinecraftServer server, ServerLevel level,
                                                        ObservationEngine observer,
                                                        ServerPlayer actor)
            throws IOException {
        OperatorWorldData disk = OperatorStorage.load(server);
        PlayerMemory memory = disk.players.get(OWNER.toString());
        if (memory == null || memory.autonomousFoldJournalId.isEmpty()
                || !disk.autonomousAnomaliesEnabled || !isFolded(level))
            return "OPERATOR_TUNNEL_CI_FAILED: first event did not survive JVM restart";
        TransactionJournal journal = TransactionJournalStorage.load(
                server, OWNER.toString(), memory.autonomousFoldJournalId);
        RelocationExecutionLedger wal = RelocationExecutionStorage.load(server, journal);
        if (!"COMMITTED".equals(wal.phase)
                || RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level))
                        .appliedCells() != 4)
            return "OPERATOR_TUNNEL_CI_FAILED: reloaded WAL/real chunks differ";
        String repeat = observer.ciAttemptAutonomousFold(
                server, actor, true, true, 24_200L);
        if (!"ALREADY_ATTEMPTED".equals(repeat))
            return "OPERATOR_TUNNEL_CI_FAILED: duplicate event gate: " + repeat;
        String rollback = TunnelFoldService.rollback(
                server, actor, memory.autonomousFoldJournalId);
        if (!rollback.startsWith("Tunnel fold rollback ROLLED_BACK")
                || !isOriginal(level))
            return "OPERATOR_TUNNEL_CI_FAILED: live rollback: " + rollback;
        return "OPERATOR_TUNNEL_CI_PHASE2_OK: persisted world/WAL loaded,"
                + " duplicate blocked, real block rollback verified";
    }

    private static String verifyRolledBackAndNoRepeat(MinecraftServer server, ServerLevel level,
                                                       ObservationEngine observer,
                                                       ServerPlayer actor)
            throws IOException {
        OperatorWorldData disk = OperatorStorage.load(server);
        PlayerMemory memory = disk.players.get(OWNER.toString());
        if (memory == null || memory.autonomousFoldJournalId.isEmpty())
            return "OPERATOR_TUNNEL_CI_FAILED: missing persisted one-shot marker";
        TransactionJournal journal = TransactionJournalStorage.load(
                server, OWNER.toString(), memory.autonomousFoldJournalId);
        RelocationExecutionLedger wal = RelocationExecutionStorage.load(server, journal);
        if (!"ROLLED_BACK".equals(wal.phase) || !isOriginal(level)
                || RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level))
                        .status() != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_TUNNEL_CI_FAILED: original blocks not durable after restart";
        String repeat = observer.ciAttemptAutonomousFold(
                server, actor, true, true, 24_400L);
        if (!"ALREADY_ATTEMPTED".equals(repeat) || !isOriginal(level))
            return "OPERATOR_TUNNEL_CI_FAILED: one-shot gate after recovery " + repeat;
        if (RelocationWatchdogStorage.scan(server).blocksNewWrites())
            return "OPERATOR_TUNNEL_CI_FAILED: recovered transaction blocks new writes";
        return "OPERATOR_TUNNEL_CI_PHASE3_OK: rollback persisted, no repeat,"
                + " no unfinished WAL, original cave unchanged";
    }

    private static void buildTestTunnel(ServerLevel level) {
        // All coordinates are in a disposable CI-owned world above normal terrain.
        // Include a stone roof high above the event so the cave-depth check
        // exercises the same heightmap logic as normal underground tunnels.
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++)
                for (int y = Y - 1; y <= Y + 13; y++)
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        for (int x = -2; x <= 2; x++)
            for (int z = -2; z <= 2; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z),
                        Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, Y + 12, z),
                        Blocks.STONE.defaultBlockState(), 2);
            }
        level.setBlock(SOURCE, Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(SOURCE2, Blocks.STONE.defaultBlockState(), 2);
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
}
