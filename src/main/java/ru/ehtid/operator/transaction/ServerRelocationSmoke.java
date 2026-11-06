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
                case 4 -> prepareOnly(server, level);
                case 5 -> crashInUnacknowledgedWorldWrite(server, level);
                case 6 -> recoverInterruptedCrash(server, level);
                case 7 -> verifyInterruptedRecoveryAfterRestart(server, level);
                default -> "OPERATOR_CI_FAILED: unknown test phase";
            };
        } catch (RuntimeException | IOException error) {
            return "OPERATOR_CI_FAILED_PHASE" + number + ": "
                    + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static String prepareAndApply(MinecraftServer server, ServerLevel level)
            throws IOException {
        String problem = prepareFixture(server, level);
        if (problem != null) return problem;
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
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

    private static String prepareOnly(MinecraftServer server, ServerLevel level)
            throws IOException {
        String problem = prepareFixture(server, level);
        if (problem != null) return problem;
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        if (RelocationExecutionStorage.exists(server, journal)
                || RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level)).status()
                        != RecoveryPlanner.Status.UNCHANGED) {
            return "OPERATOR_CI_FAILED: prepared crash fixture has changed cells or WAL";
        }
        return "OPERATOR_CI_PHASE4_OK: initial source shell and journal ready to persist";
    }

    private static String prepareFixture(MinecraftServer server, ServerLevel level)
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
        if (RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level)).status()
                != RecoveryPlanner.Status.UNCHANGED) {
            return "OPERATOR_CI_FAILED: source setup did not match saved journal";
        }
        return null;
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

    /** Real process halt after a block changed but before its WAL ACK. */
    private static String crashInUnacknowledgedWorldWrite(MinecraftServer server,
                                                          ServerLevel level)
            throws IOException {
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        if (!JOURNAL_ID.equals(journal.journalId)
                || RelocationExecutionStorage.exists(server, journal))
            return "OPERATOR_CI_FAILED: crash test journal is missing or WAL already exists";

        MinecraftSafeBlockWorld realWorld = new MinecraftSafeBlockWorld(level);
        if (RecoveryPlanner.inspect(journal, realWorld).status()
                != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_CI_FAILED: source world not persisted before crash test";

        final int crashOnWrite = 12;
        RecoveryExecutor.MutableWorld injected = new RecoveryExecutor.MutableWorld() {
            private int writes;

            @Override
            public RecoveryPlanner.State read(int x, int y, int z) {
                return realWorld.read(x, y, z);
            }

            @Override
            public boolean compareAndRestore(RecoveryPlanner.Position position,
                                             RecoveryPlanner.State expected,
                                             RecoveryPlanner.State restore) {
                boolean changed = realWorld.compareAndRestore(position, expected, restore);
                if (changed && ++writes == crashOnWrite) {
                    // This is a deliberate abrupt JVM termination; no save hooks
                    // or shutdown callbacks run. Gated by the CI flag AND world
                    // marker, no players, a fixed test journal and test world.
                    System.err.println("OPERATOR_CI_UNCLEAN_HALT_AFTER_WRITE_12");
                    System.err.flush();
                    Runtime.getRuntime().halt(137);
                }
                return changed;
            }
        };
        // CI runner expects the RCON connection to vanish, and Gradle to fail.
        SandboxTransactionEngine.apply(journal, injected, level::getGameTime,
                new DiskLedger(server));
        return "OPERATOR_CI_FAILED: forced process halt did not execute";
    }

    private static String recoverInterruptedCrash(MinecraftServer server, ServerLevel level)
            throws IOException {
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        if (!JOURNAL_ID.equals(journal.journalId)
                || !"APPLYING".equals(ledger.phase)
                || !"APPLY".equals(ledger.pendingOperation)
                || ledger.appliedWrites != 11 || ledger.totalWrites != EXPECTED_WRITES)
            return "OPERATOR_CI_FAILED: WAL did not survive abrupt shutdown as expected";

        MinecraftSafeBlockWorld world = new MinecraftSafeBlockWorld(level);
        RecoveryPlanner.Plan state = RecoveryPlanner.inspect(journal, world);
        if (!state.safeToRestore())
            return "OPERATOR_CI_FAILED: unclean stop left unknown changed blocks: "
                    + state.status() + " conflicts=" + state.conflicts()
                    + " unloaded=" + state.unloaded();

        int persistedChanges = state.appliedCells();
        SandboxTransactionEngine.Result recovered =
                SandboxTransactionEngine.rollback(journal, ledger, world,
                        level::getGameTime, new DiskLedger(server));
        if (recovered.outcome() != SandboxTransactionEngine.Outcome.ROLLED_BACK
                || RecoveryPlanner.inspect(journal, world).status()
                        != RecoveryPlanner.Status.UNCHANGED)
            return "OPERATOR_CI_FAILED: unclean rollback refused: "
                    + recovered.outcome() + " " + recovered.reason();

        return "OPERATOR_CI_PHASE6_OK: abrupt JVM exit recovered"
                + " | WAL durable intent=APPLY, ACKs=11"
                + " | loaded after restart writes=" + persistedChanges
                + " | restored=" + recovered.confirmedWrites();
    }

    private static String verifyInterruptedRecoveryAfterRestart(MinecraftServer server,
                                                                ServerLevel level)
            throws IOException {
        TransactionJournal journal = TransactionJournalStorage.loadLatest(server, OWNER);
        RelocationExecutionLedger ledger = RelocationExecutionStorage.load(server, journal);
        RecoveryPlanner.Plan state =
                RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level));
        if (!JOURNAL_ID.equals(journal.journalId)
                || !"ROLLED_BACK".equals(ledger.phase)
                || state.status() != RecoveryPlanner.Status.UNCHANGED
                || state.alreadyOriginal() != SIZE * SIZE * SIZE * 2)
            return "OPERATOR_CI_FAILED: recovery did not persist after second restart";
        return "OPERATOR_CI_PHASE7_OK: unclean-shutdown recovery persisted"
                + " | original cells=" + state.alreadyOriginal();
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
