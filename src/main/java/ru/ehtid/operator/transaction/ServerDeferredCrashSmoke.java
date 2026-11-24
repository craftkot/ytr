package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * CI-only four-JVM fault injection for a V2 cross-chunk world-field WAL.
 * Never invoked by the production scheduler. Requires the same isolated
 * marker, JVM flag, and no-connected-players check as ServerRelocationSmoke.
 */
final class ServerDeferredCrashSmoke {
    static final String OWNER = "643ed413-ed77-4530-8939-936c9b97c1b9";
    static final String JOURNAL_ID = "3c2f8310-2708-453e-9633-6bd138d5c80b";
    private static final int Y = 202;
    private static final BlockPos SOURCE = new BlockPos(256, Y, 0);
    private static final BlockPos SOURCE2 = new BlockPos(256, Y, 1);
    private static final BlockPos TARGET = new BlockPos(255, Y, 0);
    private static final BlockPos TARGET2 = new BlockPos(255, Y, 1);

    private ServerDeferredCrashSmoke() { }

    static String phase(MinecraftServer server, ServerLevel level, int phase)
            throws IOException {
        // Defense in depth: only a disposable GitHub Actions crash world.
        if (!Boolean.getBoolean("operator.reality.ciSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !server.getPlayerList().getPlayers().isEmpty()
                || !Files.isRegularFile(server.getWorldPath(LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-smoke.allow")))
            return "OPERATOR_CI_FAILED: deferred crash smoke not isolated";
        level.getChunk(15, 0);
        level.getChunk(16, 0);
        return switch (phase) {
            case 12 -> prepare(server, level);
            case 13 -> crash(server, level);
            case 14 -> recover(server, level);
            case 15 -> verify(server, level);
            default -> "OPERATOR_CI_FAILED: unknown deferred crash phase";
        };
    }

    private static TransactionJournal journal(MinecraftServer server)
            throws IOException {
        return TransactionJournalStorage.load(server, OWNER, JOURNAL_ID);
    }

    private static String prepare(MinecraftServer server, ServerLevel level)
            throws IOException {
        if (RelocationExecutionStorage.exists(server,
                fixture(level))) return "OPERATOR_CI_FAILED: deferred WAL already exists";
        if (Files.exists(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions")
                .resolve(OWNER).resolve(JOURNAL_ID + ".json")))
            return "OPERATOR_CI_FAILED: deferred journal already exists";
        for (BlockPos source : new BlockPos[]{SOURCE,SOURCE2})
            level.setBlock(source, Blocks.STONE.defaultBlockState(), 2);
        for (BlockPos target : new BlockPos[]{TARGET,TARGET2})
            level.setBlock(target, Blocks.AIR.defaultBlockState(), 2);
        TransactionJournal tx = fixture(level);
        if (!ChunkBoundaryPolicy.spansExactlyTwoChunks(tx)
                || WorldFieldSectorFootprint.sectors(tx).size() != 2
                || RecoveryPlanner.inspect(tx, new MinecraftSafeBlockWorld(level))
                        .status() != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_CI_FAILED: deferred boundary fixture is invalid";
        TransactionJournalStorage.save(server, tx);
        return "OPERATOR_CI_PHASE12_OK: four-write cross-sector journal prepared on disk";
    }

    private static TransactionJournal fixture(ServerLevel level) {
        TransactionJournal tx = TunnelFoldService.twoCellJournal(
                UUID.fromString(OWNER), level.dimension().toString(),
                SOURCE, TARGET, level.getGameTime());
        tx.journalId = JOURNAL_ID;
        tx.sourceSnapshotId = WorldFieldService.MARKER_V2;
        tx.sourceSnapshotSha256 = WorldFieldService.MARKER_V2;
        tx.sha256 = TransactionIntegrity.fingerprint(tx);
        return tx;
    }

    private static String crash(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal tx = journal(server);
        if (!SandboxTransactionEngine.isTunnelFold(tx)
                || !ChunkBoundaryPolicy.spansExactlyTwoChunks(tx)
                || RelocationExecutionStorage.exists(server, tx))
            return "OPERATOR_CI_FAILED: cross-chunk crash precondition invalid";
        MinecraftSafeBlockWorld real = new MinecraftSafeBlockWorld(level);
        if (RecoveryPlanner.inspect(tx, real).status()
                != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_CI_FAILED: pre-crash world not original";
        RecoveryExecutor.MutableWorld injected = new RecoveryExecutor.MutableWorld() {
            int writes;
            @Override public RecoveryPlanner.State read(int x, int y, int z) {
                return real.read(x,y,z);
            }
            @Override public boolean compareAndRestore(RecoveryPlanner.Position pos,
                    RecoveryPlanner.State expected, RecoveryPlanner.State restore) {
                boolean changed = real.compareAndRestore(pos, expected, restore);
                if (changed && ++writes == 3) {
                    // Two target writes, then the first source write: BOTH
                    // chunks have truly changed before the unacknowledged ACK.
                    if (!server.saveEverything(true, true, true)) {
                        System.err.println("OPERATOR_DEFERRED_CI_FLUSH_FAILED");
                        System.err.flush();
                        Runtime.getRuntime().halt(138);
                    }
                    var state = RecoveryPlanner.inspect(tx, real);
                    if (state.appliedCells() != 3) {
                        System.err.println("OPERATOR_DEFERRED_CI_APPLIED_CELL_MISMATCH");
                        System.err.flush();
                        Runtime.getRuntime().halt(139);
                    }
                    System.err.println("OPERATOR_DEFERRED_CI_TWO_CHUNKS_FLUSHED");
                    System.err.println("OPERATOR_DEFERRED_CI_HALT_BEFORE_ACK3");
                    System.err.flush();
                    Runtime.getRuntime().halt(137);
                }
                return changed;
            }
        };
        SandboxTransactionEngine.applyTunnelFold(
                tx, injected, level::getGameTime, new DiskLedger(server));
        return "OPERATOR_CI_FAILED: deferred forced hard halt did not fire";
    }

    private static String recover(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal tx = journal(server);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, tx);
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        var plan = RecoveryPlanner.inspect(tx, world);
        if (!"APPLYING".equals(ledger.phase)
                || !"APPLY".equals(ledger.pendingOperation)
                || ledger.totalWrites != 4 || ledger.appliedWrites != 2
                || !plan.safeToRestore() || plan.appliedCells() != 3)
            return "OPERATOR_CI_FAILED: deferred cold-crash WAL/preimages mismatch";
        var result = SandboxTransactionEngine.rollback(
                tx, ledger, world, level::getGameTime, new DiskLedger(server));
        if (result.outcome() != SandboxTransactionEngine.Outcome.ROLLED_BACK
                || RecoveryPlanner.inspect(tx, world).status()
                        != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_CI_FAILED: deferred cross-chunk recovery failed "
                    + result.outcome() + " " + result.reason();
        return "OPERATOR_CI_PHASE14_OK: unacknowledged source write recovered on both chunks";
    }

    private static String verify(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal tx = journal(server);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, tx);
        if (!"ROLLED_BACK".equals(ledger.phase)
                || RecoveryPlanner.inspect(tx, new MinecraftSafeBlockWorld(level))
                        .status() != RecoveryPlanner.Status.UNCHANGED
                || !level.getBlockState(SOURCE).is(Blocks.STONE)
                || !level.getBlockState(SOURCE2).is(Blocks.STONE)
                || !level.getBlockState(TARGET).isAir()
                || !level.getBlockState(TARGET2).isAir())
            return "OPERATOR_CI_FAILED: deferred rollback not durable after restart";
        return "OPERATOR_CI_PHASE15_OK: original blocks and final WAL persisted across cold restart";
    }

    private record DiskLedger(MinecraftServer server)
            implements SandboxTransactionEngine.LedgerStore {
        @Override public void create(TransactionJournal tx, RelocationExecutionLedger ledger)
                throws IOException {
            RelocationExecutionStorage.create(server, tx, ledger);
        }
        @Override public void update(TransactionJournal tx, RelocationExecutionLedger ledger)
                throws IOException {
            RelocationExecutionStorage.update(server, tx, ledger);
        }
        @Override public void flushWorld() throws IOException {
            if (!server.saveEverything(true, true, true))
                throw new IOException("Minecraft world flush returned false");
        }
    }
}
