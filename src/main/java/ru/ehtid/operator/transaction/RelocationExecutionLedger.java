package ru.ehtid.operator.transaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/**
 * Separate write-intent ledger. Unlike observation checkpoints, it records
 * whether THIS MOD actually began editing the world, making restore eligible
 * only for a specifically authorized operation.
 *
 * Mutating world state requires an explicit command and the matching ledger.
 */
public final class RelocationExecutionLedger {
    public static final int FORMAT_VERSION = 1;
    private static final Set<String> PHASES =
            Set.of("APPLYING", "COMMITTED", "ROLLING_BACK", "ROLLED_BACK");

    public int formatVersion = FORMAT_VERSION;
    public String journalId = "";
    public String journalSha256 = "";
    public String ownerUuid = "";
    public String dimension = "";
    public String phase = "APPLYING";
    public int totalWrites = 0;
    public int appliedWrites = 0;
    public int restoredWrites = 0;
    public String pendingOperation = "";
    public int pendingX, pendingY, pendingZ;
    public long updatedTick;
    public String sha256 = "";

    public RelocationExecutionLedger() { }

    public static RelocationExecutionLedger start(TransactionJournal journal,
                                                   int expectedWrites, long tick) {
        if (journal == null || !TransactionIntegrity.valid(journal)
                || !"PREPARED".equals(journal.phase)
                || expectedWrites < 1 || expectedWrites > 256) {
            throw new IllegalArgumentException("Unsafe execution input");
        }
        RelocationExecutionLedger result = new RelocationExecutionLedger();
        result.journalId = journal.journalId;
        result.journalSha256 = journal.sha256;
        result.ownerUuid = journal.ownerUuid;
        result.dimension = journal.dimension;
        result.totalWrites = expectedWrites;
        result.updatedTick = tick;
        result.sign();
        return result;
    }

    public boolean matches(TransactionJournal journal) {
        return valid() && journal != null && TransactionIntegrity.valid(journal)
                && journalId.equals(journal.journalId)
                && journalSha256.equals(journal.sha256)
                && ownerUuid.equals(journal.ownerUuid)
                && dimension.equals(journal.dimension);
    }

    public void beginWrite(String operation, int x, int y, int z, long tick) {
        if (!valid() || !pendingOperation.isEmpty()) throw new IllegalStateException("Unfinished write intent");
        if ("APPLY".equals(operation) && !"APPLYING".equals(phase))
            throw new IllegalStateException("Execution not in APPLYING phase");
        if ("RESTORE".equals(operation) && !"ROLLING_BACK".equals(phase))
            throw new IllegalStateException("Recovery not in ROLLING_BACK phase");
        if (!"APPLY".equals(operation) && !"RESTORE".equals(operation))
            throw new IllegalArgumentException("Unknown write operation");
        pendingOperation = operation;
        pendingX = x; pendingY = y; pendingZ = z;
        updatedTick = tick;
        sign();
    }

    public void acknowledgeWrite(String operation, long tick) {
        if (!valid() || !operation.equals(pendingOperation)) throw new IllegalStateException("No matching write intent");
        if ("APPLY".equals(operation)) {
            if (appliedWrites >= totalWrites) throw new IllegalStateException("Excessive application writes");
            appliedWrites++;
        } else {
            if (restoredWrites >= totalWrites) throw new IllegalStateException("Excessive recovery writes");
            restoredWrites++;
        }
        pendingOperation = "";
        updatedTick = tick;
        sign();
    }

    public void setPhase(String next, long tick) {
        if (!valid() || !pendingOperation.isEmpty()) throw new IllegalStateException("Unresolved pending write");
        boolean allowed = ("APPLYING".equals(phase)
                        && ("COMMITTED".equals(next) || "ROLLING_BACK".equals(next)))
                || ("COMMITTED".equals(phase) && "ROLLING_BACK".equals(next))
                || ("ROLLING_BACK".equals(phase) && "ROLLED_BACK".equals(next));
        if (!allowed) throw new IllegalStateException("Invalid execution transition: " + phase + " -> " + next);
        if ("COMMITTED".equals(next) && appliedWrites != totalWrites)
            throw new IllegalStateException("Cannot commit incomplete write sequence");
        phase = next;
        updatedTick = tick;
        sign();
    }

    /**
     * Resolve an interrupted APPLY/RESTORE intent after the caller has compared
     * the complete world against journal BEFORE/AFTER images.
     *
     * If a pending RESTORE is already reflected in the world, account for its
     * missing ACK before clearing the intent. If the world still contains its
     * AFTER image, the restore remains in the recovery plan and will be retried.
     */
    public void beginRollbackAfterInterruptedWrite(long tick) {
        beginRollbackAfterInterruptedWrite(tick, false);
    }

    public void beginRollbackAfterInterruptedWrite(long tick,
                                                   boolean pendingRestoreAlreadyApplied) {
        if (!valid() || !("APPLYING".equals(phase) || "ROLLING_BACK".equals(phase)))
            throw new IllegalStateException("No interrupted operation to recover");
        if (pendingRestoreAlreadyApplied && !"RESTORE".equals(pendingOperation))
            throw new IllegalStateException("No interrupted RESTORE intent to reconcile");
        if (pendingRestoreAlreadyApplied && restoredWrites >= totalWrites)
            throw new IllegalStateException("Excessive recovered RESTORE acknowledgements");
        phase = "ROLLING_BACK";
        if (pendingRestoreAlreadyApplied) restoredWrites++;
        pendingOperation = "";
        updatedTick = tick;
        sign();
    }

    public boolean valid() {
        return formatVersion == FORMAT_VERSION && journalId != null && !journalId.isBlank()
                && journalSha256 != null && journalSha256.length() == 64
                && ownerUuid != null && !ownerUuid.isBlank()
                && dimension != null && !dimension.isBlank()
                && PHASES.contains(phase)
                && totalWrites > 0 && totalWrites <= 256
                && appliedWrites >= 0 && appliedWrites <= totalWrites
                && restoredWrites >= 0 && restoredWrites <= totalWrites
                && pendingOperation != null
                && (pendingOperation.isEmpty()
                    || ("APPLYING".equals(phase) && pendingOperation.equals("APPLY"))
                    || ("ROLLING_BACK".equals(phase) && pendingOperation.equals("RESTORE")))
                && (!"COMMITTED".equals(phase) || appliedWrites == totalWrites)
                && sha256 != null && sha256.equals(fingerprint());
    }

    public void sign() {
        sha256 = fingerprint();
    }

    private String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{Integer.toString(formatVersion),
                    journalId, journalSha256, ownerUuid, dimension, phase,
                    Integer.toString(totalWrites), Integer.toString(appliedWrites),
                    Integer.toString(restoredWrites), pendingOperation,
                    Integer.toString(pendingX), Integer.toString(pendingY), Integer.toString(pendingZ),
                    Long.toString(updatedTick)}) {
                byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
                digest.update((byte)(bytes.length >>> 24));
                digest.update((byte)(bytes.length >>> 16));
                digest.update((byte)(bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
