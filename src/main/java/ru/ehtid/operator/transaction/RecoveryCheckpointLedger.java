package ru.ehtid.operator.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Tamper-evident, append-only logical checkpoint ledger for a PREPARED journal.
 *
 * A checkpoint records an observation, NOT permission to mutate blocks.
 * Its chain is persisted via an atomic file replacement by the storage layer.
 * The journal SHA-256 is bound into every event, so a ledger cannot be reused
 * with another transaction or another player's world.
 */
public final class RecoveryCheckpointLedger {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_EVENTS = 128;

    private static final Set<String> ALLOWED_PHASES = Set.of(
            "PREPARED", "UNCHANGED", "RESTORE_NEEDED",
            "CONFLICT", "UNLOADED", "UNSUPPORTED_BLOCK_ENTITY",
            "INVALID_JOURNAL"
    );

    public int formatVersion = FORMAT_VERSION;
    public String journalId = "";
    public String journalSha256 = "";
    public String ownerUuid = "";
    public String dimension = "";
    public List<Checkpoint> checkpoints = new ArrayList<>();

    public record Checkpoint(int sequence, long gameTick, String phase,
                             int examined, int alreadyOriginal, int predictedWrites,
                             int conflicts, int unloaded,
                             String previousHash, String hash) { }

    private RecoveryCheckpointLedger() { }

    /** Create the initial record only after the BEFORE-image journal is durable. */
    public static RecoveryCheckpointLedger initialize(TransactionJournal journal, long gameTick) {
        if (!TransactionIntegrity.valid(journal) || !"PREPARED".equals(journal.phase)) {
            throw new IllegalArgumentException("A verified PREPARED journal is required");
        }
        RecoveryCheckpointLedger ledger = new RecoveryCheckpointLedger();
        ledger.journalId = journal.journalId;
        ledger.journalSha256 = journal.sha256;
        ledger.ownerUuid = journal.ownerUuid;
        ledger.dimension = journal.dimension;
        ledger.append("PREPARED", gameTick, 0, 0, 0, 0, 0);
        return ledger;
    }

    /** Audit-only transition: no APPLYING or RECOVERING phase exists in this release. */
    public void appendAudit(TransactionJournal journal, long gameTick,
                            RecoveryPlanner.Plan plan) {
        if (!matches(journal) || !valid() || plan == null) {
            throw new IllegalArgumentException("Journal/ledger mismatch or damaged checkpoints");
        }
        String phase = plan.status().name();
        if (!ALLOWED_PHASES.contains(phase)) {
            throw new IllegalArgumentException("Unrecognized audit outcome");
        }
        append(phase, gameTick, plan.examined(), plan.alreadyOriginal(),
                plan.appliedCells(), plan.conflicts(), plan.unloaded());
    }

    public boolean matches(TransactionJournal journal) {
        return journal != null && TransactionIntegrity.valid(journal)
                && "PREPARED".equals(journal.phase)
                && journal.journalId.equals(journalId)
                && journal.sha256.equals(journalSha256)
                && journal.ownerUuid.equals(ownerUuid)
                && journal.dimension.equals(dimension);
    }

    public Checkpoint latest() {
        if (!valid()) throw new IllegalStateException("Corrupted recovery checkpoint chain");
        return checkpoints.get(checkpoints.size() - 1);
    }

    public boolean valid() {
        if (formatVersion != FORMAT_VERSION || journalId == null || journalId.isBlank()
                || journalSha256 == null || journalSha256.length() != 64
                || ownerUuid == null || ownerUuid.isBlank()
                || dimension == null || dimension.isBlank()
                || checkpoints == null || checkpoints.isEmpty()
                || checkpoints.size() > MAX_EVENTS) return false;

        String previous = journalSha256;
        long previousTick = Long.MIN_VALUE;
        for (int i = 0; i < checkpoints.size(); i++) {
            Checkpoint checkpoint = checkpoints.get(i);
            if (checkpoint == null || checkpoint.sequence() != i
                    || checkpoint.phase() == null
                    || !ALLOWED_PHASES.contains(checkpoint.phase())
                    || (i == 0 && !"PREPARED".equals(checkpoint.phase()))
                    || (i > 0 && "PREPARED".equals(checkpoint.phase()))
                    || checkpoint.gameTick() < previousTick
                    || checkpoint.examined() < 0 || checkpoint.examined() > TransactionJournal.MAX_TOTAL_CELLS
                    || checkpoint.alreadyOriginal() < 0 || checkpoint.predictedWrites() < 0
                    || checkpoint.conflicts() < 0 || checkpoint.unloaded() < 0
                    || (long) checkpoint.alreadyOriginal() + checkpoint.predictedWrites()
                            + checkpoint.conflicts() + checkpoint.unloaded()
                            > checkpoint.examined()
                    || !previous.equals(checkpoint.previousHash())
                    || !digest(this, checkpoint, previous).equals(checkpoint.hash())) {
                return false;
            }
            previousTick = checkpoint.gameTick();
            previous = checkpoint.hash();
        }
        return true;
    }

    private void append(String phase, long gameTick, int examined, int originals,
                        int predictedWrites, int conflicts, int unloaded) {
        if (checkpoints.size() >= MAX_EVENTS) {
            throw new IllegalStateException("Audit ledger limit reached; archive and start a new journal");
        }
        if (!checkpoints.isEmpty() && gameTick < checkpoints.get(checkpoints.size() - 1).gameTick()) {
            throw new IllegalArgumentException("Non-monotonic world tick");
        }
        String previous = checkpoints.isEmpty()
                ? journalSha256 : checkpoints.get(checkpoints.size() - 1).hash();
        Checkpoint raw = new Checkpoint(checkpoints.size(), gameTick, phase,
                examined, originals, predictedWrites, conflicts, unloaded,
                previous, "");
        checkpoints.add(new Checkpoint(raw.sequence(), raw.gameTick(), raw.phase(),
                raw.examined(), raw.alreadyOriginal(), raw.predictedWrites(),
                raw.conflicts(), raw.unloaded(), raw.previousHash(), digest(this, raw, previous)));
    }

    private static String digest(RecoveryCheckpointLedger ledger, Checkpoint checkpoint,
                                 String previousHash) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            field(sha, Integer.toString(ledger.formatVersion));
            field(sha, ledger.journalId);
            field(sha, ledger.journalSha256);
            field(sha, ledger.ownerUuid);
            field(sha, ledger.dimension);
            field(sha, previousHash);
            field(sha, Integer.toString(checkpoint.sequence()));
            field(sha, Long.toString(checkpoint.gameTick()));
            field(sha, checkpoint.phase());
            field(sha, Integer.toString(checkpoint.examined()));
            field(sha, Integer.toString(checkpoint.alreadyOriginal()));
            field(sha, Integer.toString(checkpoint.predictedWrites()));
            field(sha, Integer.toString(checkpoint.conflicts()));
            field(sha, Integer.toString(checkpoint.unloaded()));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void field(MessageDigest digest, String text) {
        byte[] value = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (value.length >>> 24));
        digest.update((byte) (value.length >>> 16));
        digest.update((byte) (value.length >>> 8));
        digest.update((byte) value.length);
        digest.update(value);
    }
}
