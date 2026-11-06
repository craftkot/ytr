package ru.ehtid.operator.transaction;

import java.io.IOException;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import ru.ehtid.operator.storage.RecoveryCheckpointStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Explicit, read-only audit of the latest PREPARED transaction.
 * Stores a hash-chained observation checkpoint, never a write authorization.
 */
public final class RecoveryAuditService {
    private RecoveryAuditService() { }

    public static String audit(MinecraftServer server, ServerPlayer player) {
        TransactionJournal journal;
        try {
            journal = TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
        } catch (IOException error) {
            return "Recovery audit refused: " + error.getMessage();
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().toString().equals(journal.dimension)) {
            return "Recovery audit refused: journal belongs to another dimension";
        }

        RecoveryPlanner.Plan plan;
        try {
            plan = RecoveryPlanner.inspect(journal, new MinecraftRecoveryWorldView(level));
        } catch (RuntimeException failure) {
            return "Recovery audit refused: world state could not be read safely";
        }

        String risks;
        try {
            RelocationHazardAnalyzer.Risks sourceRisks =
                    RelocationHazardAnalyzer.inspect(journal);
            RecoveryBoundaryInspector.Assessment boundary =
                    RecoveryBoundaryInspector.inspect(level, journal);
            risks = " | source/target hazards: " + sourceRisks.summary()
                    + " | neighborhood: " + boundary.summary()
                    + " | no hazard scan guarantees safe relocation";
        } catch (RuntimeException inspectionFailure) {
            risks = " | neighborhood risk scan failed: REVIEW REQUIRED";
        }

        String checkpointStatus;
        try {
            RecoveryCheckpointLedger ledger = RecoveryCheckpointStorage.appendAudit(
                    server, journal, level.getGameTime(), plan
            );
            checkpointStatus = " | durable checkpoint #" + ledger.latest().sequence()
                    + " | history: " + ledger.checkpoints.size();
        } catch (IOException failure) {
            // Audit itself stays read-only and informative. Never assert that
            // the result is crash-resumable if the checkpoint did not persist.
            checkpointStatus = " | CHECKPOINT NOT SAVED: " + failure.getMessage();
        }

        return "Recovery audit " + plan.status()
                + " | examined: " + plan.examined()
                + " | original: " + plan.alreadyOriginal()
                + " | possible prior-transaction writes: " + plan.appliedCells()
                + " | conflicts: " + plan.conflicts()
                + " | inaccessible cells: " + plan.unloaded()
                + " | modeled restore actions: " + plan.changes().size()
                + checkpointStatus
                + risks
                + " | a matching AFTER state does not prove Operator caused it"
                + " | automatic recovery and live block writes disabled";
    }

    /** Inspect the persisted recovery audit history after server restart. */
    public static String checkpointStatus(MinecraftServer server, ServerPlayer player) {
        try {
            TransactionJournal journal = TransactionJournalStorage.loadLatest(
                    server, player.getUUID().toString());
            RecoveryCheckpointLedger ledger =
                    RecoveryCheckpointStorage.loadVerified(server, journal);
            RecoveryCheckpointLedger.Checkpoint latest = ledger.latest();
            return "Recovery checkpoint journal " + journal.journalId
                    + " | phase: " + latest.phase()
                    + " | checkpoint: " + latest.sequence()
                    + " | saved events: " + ledger.checkpoints.size()
                    + " | game tick: " + latest.gameTick()
                    + " | examined: " + latest.examined()
                    + " | possible writes: " + latest.predictedWrites()
                    + " | conflicts: " + latest.conflicts()
                    + " | missing: " + latest.unloaded()
                    + " | verified hash: " + latest.hash().substring(0, 12)
                    + " | no world mutation authorized";
        } catch (IOException failure) {
            return "Recovery checkpoint unavailable: " + failure.getMessage()
                    + " | use /operator transaction audit to create or update a checkpoint";
        }
    }
}
