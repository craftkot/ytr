package ru.ehtid.operator.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.transaction.RelocationExecutionLedger;
import ru.ehtid.operator.transaction.TransactionJournal;

/**
 * Read-only, bounded census of execution WAL files for the entire server.
 *
 * An offline player's unfinished APPLYING/ROLLING_BACK operation must not
 * disappear from the operator's view because another player's transaction is
 * the newest. Missing or corrupt before-images fail closed. No chunks load and
 * no blocks are ever changed by this scanner.
 */
public final class RelocationWatchdogStorage {
    public static final int MAX_OWNERS = 128;
    public static final int MAX_EXECUTIONS = 512;
    private static final int MAX_DISPLAY_ENTRIES = 4;

    private RelocationWatchdogStorage() { }

    public record Entry(String owner, String journalId, String phase,
                        int applied, int expected, int restored, boolean pending) { }

    public record Report(List<Entry> entries, int total, int unfinished,
                         int committed, int rolledBack, int invalid,
                         boolean limitReached) {
        public Report {
            entries = List.copyOf(entries);
        }

        public boolean blocksNewWrites() {
            return unfinished != 0 || invalid != 0 || limitReached;
        }

        public String summary() {
            StringBuilder output = new StringBuilder("Sandbox WAL monitor: ")
                    .append(total).append(" checked, ")
                    .append(unfinished).append(" unfinished, ")
                    .append(invalid).append(" invalid, ")
                    .append(committed).append(" committed, ")
                    .append(rolledBack).append(" rolled back");
            if (limitReached) output.append(" | SCAN LIMIT EXCEEDED");
            if (blocksNewWrites()) output.append(" | NEW SANDBOX WRITES BLOCKED");
            int shown = 0;
            for (Entry entry : entries) {
                if (shown++ >= MAX_DISPLAY_ENTRIES) break;
                output.append(" | ").append(entry.phase())
                        .append(" owner=").append(entry.owner())
                        .append(" journal=").append(entry.journalId())
                        .append(" apply=").append(entry.applied()).append("/")
                        .append(entry.expected())
                        .append(" restore=").append(entry.restored());
                if (entry.pending()) output.append(" PENDING_INTENT");
            }
            return output.toString();
        }
    }

    public static Report scan(MinecraftServer server) throws IOException {
        return scanWorldDirectory(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality"));
    }

    /**
     * Package-private for restart/ownership/corruption tests without a Minecraft
     * server. Path must be an already selected world-local operator_reality root.
     */
    static Report scanWorldDirectory(Path root) throws IOException {
        Path executions = root.resolve("executions");
        if (!Files.exists(executions)) return new Report(List.of(), 0, 0, 0, 0, 0, false);
        if (Files.isSymbolicLink(executions) || !Files.isDirectory(executions))
            throw new IOException("Execution directory is not a regular directory");

        List<Entry> entries = new ArrayList<>();
        int total = 0, unfinished = 0, committed = 0, rolledBack = 0, invalid = 0;
        boolean limitReached = false;
        List<Path> owners = boundedList(executions, MAX_OWNERS + 1);
        if (owners.size() > MAX_OWNERS) limitReached = true;

        for (Path ownerDir : owners.stream().limit(MAX_OWNERS).toList()) {
            String owner = ownerDir.getFileName().toString();
            if (Files.isSymbolicLink(ownerDir) || !Files.isDirectory(ownerDir)
                    || !canonicalUuid(owner)) {
                total++;
                invalid++;
                entries.add(new Entry(owner, "unknown", "INVALID_OWNER", 0, 0, 0, false));
                continue;
            }
            int remaining = MAX_EXECUTIONS - total;
            if (remaining <= 0) {
                limitReached = true;
                break;
            }
            List<Path> walFiles = boundedList(ownerDir, remaining + 1);
            if (walFiles.size() > remaining) limitReached = true;
            for (Path walFile : walFiles.stream().limit(remaining).toList()) {
                String name = walFile.getFileName().toString();
                // Ignore stray temporary files left by a crashed writer, but
                // count malformed or unexpected persistent files as unsafe.
                if (name.startsWith("execution-") && name.endsWith(".tmp")) continue;
                total++;
                String id = name.endsWith(".json")
                        ? name.substring(0, name.length() - 5) : name;
                if (Files.isSymbolicLink(walFile) || !Files.isRegularFile(walFile)
                        || !name.endsWith(".json") || !canonicalUuid(id)) {
                    invalid++;
                    entries.add(new Entry(owner, id, "INVALID_FILE", 0, 0, 0, false));
                    continue;
                }
                try {
                    Path preimage = root.resolve("transactions").resolve(owner)
                            .resolve(id + ".json");
                    if (Files.isSymbolicLink(preimage))
                        throw new IOException("Transaction preimage is a symbolic link");
                    TransactionJournal journal =
                            TransactionJournalStorage.read(preimage, owner, id);
                    RelocationExecutionLedger ledger =
                            RelocationExecutionStorage.read(walFile, journal);
                    switch (ledger.phase) {
                        case "APPLYING", "ROLLING_BACK" -> {
                            unfinished++;
                            entries.add(new Entry(owner, id, ledger.phase,
                                    ledger.appliedWrites, ledger.totalWrites,
                                    ledger.restoredWrites,
                                    !ledger.pendingOperation.isEmpty()));
                        }
                        case "COMMITTED" -> committed++;
                        case "ROLLED_BACK" -> rolledBack++;
                        default -> throw new IOException("Invalid phase");
                    }
                } catch (IOException | RuntimeException damaged) {
                    invalid++;
                    entries.add(new Entry(owner, id, "INVALID_WAL_OR_JOURNAL",
                            0, 0, 0, false));
                }
            }
            if (limitReached) break;
        }
        entries.sort(Comparator.comparing(Entry::phase)
                .thenComparing(Entry::owner).thenComparing(Entry::journalId));
        return new Report(entries, total, unfinished, committed, rolledBack,
                invalid, limitReached);
    }

    private static List<Path> boundedList(Path directory, int maximum) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.limit(maximum).sorted(Comparator.comparing(
                    path -> path.getFileName().toString())).toList();
        }
    }

    private static boolean canonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
