package ru.ehtid.operator.transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic recovery planning for an interrupted whole-AABB relocation.
 *
 * This engine is independent of Minecraft and never mutates the world.
 * An observed cell must match either its journal BEFORE-image or the exact
 * predicted AFTER-image of our relocation. All other values are conflicts:
 * never overwrite third-party edits or potentially new inventory items.
 */
public final class RecoveryPlanner {
    private RecoveryPlanner() { }

    public record State(String blockId, String blockState, String blockEntitySnbt) {
        public State {
            if (blockId == null || blockState == null)
                throw new IllegalArgumentException("Incomplete block state");
        }

        public static State of(TransactionJournal.Cell cell) {
            return new State(cell.blockId(), cell.blockState(), cell.blockEntitySnbt());
        }

        public static State air() {
            return new State("minecraft:air", "Block{minecraft:air}", null);
        }
    }

    public interface WorldView {
        /**
         * Returns null if the position is unloaded or inaccessible.
         * Other errors may be propagated; they must not be interpreted as air.
         */
        State read(int x, int y, int z);
    }

    public record Position(int x, int y, int z) { }
    public record Change(Position position, State applied, State before) { }

    public enum Status {
        INVALID_JOURNAL, UNSUPPORTED_BLOCK_ENTITY, UNLOADED, CONFLICT, UNCHANGED, RESTORE_NEEDED
    }

    public record Plan(Status status, int examined, int alreadyOriginal,
                       int appliedCells, int conflicts, int unloaded,
                       List<Change> changes) {
        public Plan {
            changes = List.copyOf(changes);
        }
        public boolean safeToRestore() {
            return status == Status.RESTORE_NEEDED || status == Status.UNCHANGED;
        }
    }

    public static Plan inspect(TransactionJournal journal, WorldView world) {
        if (world == null || !TransactionIntegrity.valid(journal)
                || !"PREPARED".equals(journal.phase))
            return new Plan(Status.INVALID_JOURNAL, 0, 0, 0, 0, 0, List.of());

        // Block-entity NBT usually embeds original world coordinates.
        // Until we have a verified relocation-specific NBT rewrite/restore
        // adapter, reporting it recoverable would risk inventory duplication.
        if (journal.source.before.stream().anyMatch(cell -> cell.blockEntitySnbt() != null))
            return new Plan(Status.UNSUPPORTED_BLOCK_ENTITY, 0, 0, 0, 0, 0, List.of());

        if ((long) journal.source.minX + journal.offsetX != journal.target.minX
                || (long) journal.source.minY + journal.offsetY != journal.target.minY
                || (long) journal.source.minZ + journal.offsetZ != journal.target.minZ
                || (long) journal.source.maxX + journal.offsetX != journal.target.maxX
                || (long) journal.source.maxY + journal.offsetY != journal.target.maxY
                || (long) journal.source.maxZ + journal.offsetZ != journal.target.maxZ)
            return new Plan(Status.INVALID_JOURNAL, 0, 0, 0, 0, 0, List.of());

        Map<Position, State> originalSource = new HashMap<>();
        for (TransactionJournal.Cell cell : journal.source.before) {
            originalSource.put(new Position(cell.x(), cell.y(), cell.z()), State.of(cell));
        }

        int examined = 0, originals = 0, applied = 0, conflicts = 0, unloaded = 0;
        List<Change> changes = new ArrayList<>();

        for (TransactionJournal.Cell cell : TransactionJournal.reversePreimages(journal)) {
            boolean target = inRegion(cell.x(), cell.y(), cell.z(), journal.target);
            State expectedAfter;
            if (target) {
                long sx = (long) cell.x() - journal.offsetX;
                long sy = (long) cell.y() - journal.offsetY;
                long sz = (long) cell.z() - journal.offsetZ;
                if (sx < Integer.MIN_VALUE || sx > Integer.MAX_VALUE
                        || sy < Integer.MIN_VALUE || sy > Integer.MAX_VALUE
                        || sz < Integer.MIN_VALUE || sz > Integer.MAX_VALUE) {
                    return new Plan(Status.INVALID_JOURNAL, 0, 0, 0, 0, 0, List.of());
                }
                expectedAfter = originalSource.get(new Position((int) sx, (int) sy, (int) sz));
                if (expectedAfter == null) {
                    return new Plan(Status.INVALID_JOURNAL, 0, 0, 0, 0, 0, List.of());
                }
            } else {
                expectedAfter = State.air();
            }

            State observed = world.read(cell.x(), cell.y(), cell.z());
            examined++;
            if (observed == null) {
                unloaded++;
                continue;
            }
            State original = State.of(cell);
            if (observed.equals(original)) {
                originals++;
            } else if (observed.equals(expectedAfter)) {
                applied++;
                changes.add(new Change(new Position(cell.x(), cell.y(), cell.z()),
                        expectedAfter, original));
            } else {
                conflicts++;
            }
        }

        Status status = unloaded > 0 ? Status.UNLOADED
                : conflicts > 0 ? Status.CONFLICT
                : applied > 0 ? Status.RESTORE_NEEDED
                : Status.UNCHANGED;
        // A partially unusable plan is never returned as an executable subset.
        return new Plan(status, examined, originals, applied, conflicts, unloaded,
                (unloaded > 0 || conflicts > 0) ? List.of() : changes);
    }

    private static boolean inRegion(int x, int y, int z, TransactionJournal.Region r) {
        return x >= r.minX && x <= r.maxX && y >= r.minY && y <= r.maxY
                && z >= r.minZ && z <= r.maxZ;
    }
}
