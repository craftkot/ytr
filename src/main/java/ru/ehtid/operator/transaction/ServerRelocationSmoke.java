package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Isolated, explicitly enabled integration test against an actual dedicated
 * Minecraft server and its persistent chunk files.
 *
 * Requires BOTH -Doperator.reality.ciSmoke=true and a marker file in the
 * WORLD directory. Called only through an administrator/console RCON command.
 * Never used during normal gameplay or automated Operator decisions.
 */
public final class ServerRelocationSmoke {
    private static final String OWNER = "18762a20-b35d-48af-9807-0c995ea0d853";
    private static final String JOURNAL_ID = "0dc225ce-087f-4bf1-b24a-940b130230fe";
    private static final String SNAPSHOT_ID = "782af254-5ae5-4112-b41d-064344c58b60";
    private static final int SOURCE_X = 0, TARGET_X = 32;
    private static final int Y = 200, Z = 0;
    private static final int SIZE = 3;
    private static final int SHELL_BLOCKS = 26;
    private static final int EXPECTED_WRITES = SHELL_BLOCKS * 2;

    private ServerRelocationSmoke() { }

    public static String phase(MinecraftServer server, int number) {
        if (!Boolean.getBoolean("operator.reality.ciSmoke")) {
            return "OPERATOR_CI_DENIED: JVM flag absent";
        }
        Path marker = server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("ci-smoke.allow");
        if (!Files.isRegularFile(marker)) {
            return "OPERATOR_CI_DENIED: isolated test-world marker absent";
        }
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            return "OPERATOR_CI_DENIED: players are connected";
        }
        ServerLevel level = server.overworld();
        if (level.getMinY() > Y || level.getMaxY() <= Y + 2) {
            return "OPERATOR_CI_FAILED: unexpected world build height";
        }
        try {
            // Test-only: explicitly load both chunks, then verify persistence
            // by starting brand-new server processes between test phases.
            level.getChunk(SOURCE_X >> 4, Z >> 4);
            level.getChunk(TARGET_X >> 4, Z >> 4);
            return switch (number) {
                case 1 -> prepareAndApply(server, level);
                case 2 -> inspectAndRollback(server, level);
                case 3 -> verifyAfterRestart(server, level);
                default -> "OPERATOR_CI_FAILED: unknown test phase";
            };
        } catch (RuntimeException | IOException error) {
            return "OPERATOR_CI_FAILED_PHASE" + number + ": "
                    + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String prepareAndApply(MinecraftServer server, ServerLevel level)
            throws IOException {
        if (Files.exists(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions")
                .resolve(OWNER).resolve(JOURNAL_ID + ".json"))) {
            return "OPERATOR_CI_FAILED: existing test transaction found";
        }
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    // CI world only: clear exactly the two bounded test AABBs.
                    level.setBlock(new BlockPos(SOURCE_X + x, Y + y, Z + z),
                            Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(TARGET_X + x, Y + y, Z + z),
                            Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    if (x == 1 && y == 1 && z == 1) continue;
                    if (!level.setBlock(new BlockPos(SOURCE_X + x, Y + y, Z + z),
                            Blocks.STONE.defaultBlockState(), 2)) {
                        return "OPERATOR_CI_FAILED: unable to build source shell";
                    }
                }
            }
        }

        TransactionJournal journal = journal(level);
        TransactionJournalStorage.save(server, journal);
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        if (RecoveryPlanner.inspect(journal, world).status()
                != RecoveryPlanner.Status.UNCHANGED) {
            return "OPERATOR_CI_FAILED: source setup did not match saved journal";
        }
        SandboxTransactionEngine.Result moved =
                SandboxTransactionEngine.apply(journal, world, level::getGameTime,
                        new DiskLedger(server));
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        if (!moved.committed() || moved.transferredBlocks() != SHELL_BLOCKS
                || moved.confirmedWrites() != EXPECTED_WRITES
                || !"COMMITTED".equals(ledger.phase)
                || RecoveryPlanner.inspect(journal, world).appliedCells() != EXPECTED_WRITES) {
            return "OPERATOR_CI_FAILED: apply " + moved.outcome() + " " + moved.reason()
                    + " | ledger " + ledger.phase;
        }
        return "OPERATOR_CI_PHASE1_OK: live blocks=" + moved.transferredBlocks()
                + " verified WAL writes=" + moved.confirmedWrites();
    }

    private static String inspectAndRollback(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        if (!JOURNAL_ID.equals(journal.journalId))
            return "OPERATOR_CI_FAILED: wrong persisted journal after restart";
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        RecoveryPlanner.Plan applied = RecoveryPlanner.inspect(journal, world);
        if (applied.status() != RecoveryPlanner.Status.RESTORE_NEEDED
                || applied.appliedCells() != EXPECTED_WRITES) {
            return "OPERATOR_CI_FAILED: block states not persisted after restart: "
                    + applied.status() + " " + applied.appliedCells();
        }
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        if (!"COMMITTED".equals(ledger.phase)
                || ledger.appliedWrites != EXPECTED_WRITES) {
            return "OPERATOR_CI_FAILED: WAL not committed after restart";
        }
        SandboxTransactionEngine.Result restored =
                SandboxTransactionEngine.rollback(journal, ledger, world,
                        level::getGameTime, new DiskLedger(server));
        if (restored.outcome() != SandboxTransactionEngine.Outcome.ROLLED_BACK
                || restored.confirmedWrites() != EXPECTED_WRITES
                || RecoveryPlanner.inspect(journal, world).status()
                        != RecoveryPlanner.Status.UNCHANGED) {
            return "OPERATOR_CI_FAILED: rollback " + restored.outcome()
                    + " " + restored.reason();
        }
        return "OPERATOR_CI_PHASE2_OK: persisted blocks verified; rollback="
                + restored.confirmedWrites();
    }

    private static String verifyAfterRestart(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        RecoveryPlanner.Plan result =
                RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level));
        if (!JOURNAL_ID.equals(journal.journalId)
                || !"ROLLED_BACK".equals(ledger.phase)
                || result.status() != RecoveryPlanner.Status.UNCHANGED
                || result.alreadyOriginal() != SIZE * SIZE * SIZE * 2) {
            return "OPERATOR_CI_FAILED: rollback did not persist: "
                    + ledger.phase + " " + result.status();
        }
        return "OPERATOR_CI_PHASE3_OK: recovered blocks persisted across restart; cells="
                + result.alreadyOriginal();
    }

    private static TransactionJournal journal(ServerLevel level) {
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = JOURNAL_ID;
        journal.ownerUuid = OWNER;
        journal.sourceSnapshotId = SNAPSHOT_ID;
        journal.sourceSnapshotSha256 = "CI_NON_PLAYER_SOURCE";
        journal.dimension = level.dimension().toString();
        journal.capturedAtTick = level.getGameTime();
        journal.offsetX = TARGET_X - SOURCE_X;
        journal.source.minX = SOURCE_X; journal.source.maxX = SOURCE_X + SIZE - 1;
        journal.source.minY = Y; journal.source.maxY = Y + SIZE - 1;
        journal.source.minZ = Z; journal.source.maxZ = Z + SIZE - 1;
        journal.target.minX = TARGET_X; journal.target.maxX = TARGET_X + SIZE - 1;
        journal.target.minY = Y; journal.target.maxY = Y + SIZE - 1;
        journal.target.minZ = Z; journal.target.maxZ = Z + SIZE - 1;
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    var source = world.read(SOURCE_X + x, Y + y, Z + z);
                    var target = world.read(TARGET_X + x, Y + y, Z + z);
                    journal.source.before.add(new TransactionJournal.Cell(
                            SOURCE_X + x, Y + y, Z + z, source.blockId(),
                            source.blockState(), source.blockEntitySnbt()));
                    journal.target.before.add(new TransactionJournal.Cell(
                            TARGET_X + x, Y + y, Z + z, target.blockId(),
                            target.blockState(), target.blockEntitySnbt()));
                }
            }
        }
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }

    private record DiskLedger(MinecraftServer server)
            implements SandboxTransactionEngine.LedgerStore {
        @Override
        public void create(TransactionJournal journal, RelocationExecutionLedger ledger)
                throws IOException {
            RelocationExecutionStorage.create(server, journal, ledger);
        }

        @Override
        public void update(TransactionJournal journal, RelocationExecutionLedger ledger)
                throws IOException {
            RelocationExecutionStorage.update(server, journal, ledger);
        }
    }
}
