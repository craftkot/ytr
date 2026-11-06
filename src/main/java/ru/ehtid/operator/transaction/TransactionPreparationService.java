package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import ru.ehtid.operator.snapshot.StructureSnapshot;
import ru.ehtid.operator.snapshot.StructureSnapshotService;
import ru.ehtid.operator.storage.StructureSnapshotStorage;
import ru.ehtid.operator.storage.RecoveryCheckpointStorage;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Read-only write-ahead journal preparation.
 *
 * No automatic mutation exists in 0.12. Preparing records BOTH source and
 * target full AABBs, including air, terrain, states and block entity NBT.
 * This is a prerequisite for recovery, not yet a recovery implementation.
 */
public final class TransactionPreparationService {
    public static final int MAX_SINGLE_NBT_BYTES = 256 * 1024;

    private TransactionPreparationService() { }

    public static Result prepare(MinecraftServer server, ServerPlayer player,
                                 int anchorX, int anchorY, int anchorZ) {
        // The manual sandbox rollback currently operates on the latest prepared
        // journal. Do not allow a newer journal to hide an unfinished operation.
        try {
            TransactionJournal latest =
                    TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
            if (RelocationExecutionStorage.exists(server, latest)) {
                var execution = RelocationExecutionStorage.load(server, latest);
                if (!"ROLLED_BACK".equals(execution.phase)) {
                    return new Result(false, "An existing sandbox transfer is "
                            + execution.phase + "; rollback it before preparing a new transaction");
                }
            }
        } catch (IOException unavailable) {
            if (!"No prepared journals".equals(unavailable.getMessage())) {
                return new Result(false, "Prior journal or sandbox ledger is unreadable: "
                        + unavailable.getMessage() + "; refusing to hide it with a new journal");
            }
            // A fresh world with no saved journals is permitted to prepare one.
        }

        StructureSnapshot snapshot;
        try {
            snapshot = StructureSnapshotStorage.loadLatest(
                    server, player.getUUID().toString());
        } catch (IOException exception) {
            return new Result(false, "Cannot load source snapshot: " + exception.getMessage());
        }

        var preview = StructureSnapshotService.preflight(
                server, player, anchorX, anchorY, anchorZ);
        if (!preview.success()) {
            return new Result(false, "Transaction rejected: " + preview.message());
        }

        if (snapshot.blocks == null || snapshot.blocks.isEmpty()
                || snapshot.blockCount() > TransactionJournal.MAX_CELLS_PER_REGION)
            return new Result(false, "Invalid or oversized source snapshot");

        long sx = (long) snapshot.maxX - snapshot.minX + 1L;
        long sy = (long) snapshot.maxY - snapshot.minY + 1L;
        long sz = (long) snapshot.maxZ - snapshot.minZ + 1L;
        long sourceVolume = sx * sy * sz;
        if (sx <= 0 || sy <= 0 || sz <= 0 || sourceVolume <= 0
                || sourceVolume > TransactionJournal.MAX_CELLS_PER_REGION)
            return new Result(false, "Source AABB exceeds the 4096-cell capture limit");

        long newMaxX = (long) anchorX + sx - 1L;
        long newMaxY = (long) anchorY + sy - 1L;
        long newMaxZ = (long) anchorZ + sz - 1L;
        if (newMaxX > Integer.MAX_VALUE || newMaxY > Integer.MAX_VALUE
                || newMaxZ > Integer.MAX_VALUE)
            return new Result(false, "Target bounds overflow");
        if (sourceVolume * 2L > TransactionJournal.MAX_TOTAL_CELLS)
            return new Result(false, "Transaction exceeds combined capture limit");

        ServerLevel level = (ServerLevel) player.level();
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = UUID.randomUUID().toString();
        journal.ownerUuid = player.getUUID().toString();
        journal.sourceSnapshotId = snapshot.snapshotId;
        journal.sourceSnapshotSha256 = snapshot.sha256;
        journal.dimension = snapshot.dimension;
        journal.capturedAtTick = level.getGameTime();
        journal.offsetX = anchorX - snapshot.minX;
        journal.offsetY = anchorY - snapshot.minY;
        journal.offsetZ = anchorZ - snapshot.minZ;
        journal.source = region(snapshot.minX, snapshot.minY, snapshot.minZ,
                snapshot.maxX, snapshot.maxY, snapshot.maxZ);
        journal.target = region(anchorX, anchorY, anchorZ,
                (int) newMaxX, (int) newMaxY, (int) newMaxZ);

        try {
            captureRegion(level, journal.source, journal, false);
            // Only fully empty target AABBs are eligible; an occupied gap
            // between tracked placed blocks must not be destroyed.
            captureRegion(level, journal.target, journal, true);
        } catch (CaptureRejected exception) {
            return new Result(false, "Transaction rejected: " + exception.getMessage());
        }

        if (!TransactionJournal.hasCompleteDisjointPreimages(journal))
            return new Result(false, "Transaction rejected: incomplete or overlapping before-images");
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        try {
            Path saved = TransactionJournalStorage.save(server, journal);
            // The journal is durable first; its checkpoint is a separate
            // recoverable sidecar. Never claim both persisted if this fails.
            try {
                RecoveryCheckpointStorage.initialize(server, journal);
            } catch (IOException checkpointFailure) {
                return new Result(false, "Before-images saved to " + saved.getFileName()
                        + " but recovery checkpoint initialization failed: "
                        + checkpointFailure.getMessage()
                        + " | no world changes or recovery authorization");
            }
            return new Result(true,
                    "PREPARED " + saved.getFileName()
                            + " | checkpoint #0 saved"
                            + " | source cells: " + journal.source.before.size()
                            + " | destination cells: " + journal.target.before.size()
                            + " | block entities: " + journal.blockEntityCount
                            + " | SHA-256: " + journal.sha256.substring(0, 12)
                            + " | read-only; no blocks modified");
        } catch (IOException error) {
            return new Result(false, "Could not persist journal: " + error.getMessage());
        }
    }

    public static Result status(MinecraftServer server, ServerPlayer player) {
        try {
            TransactionJournal journal = TransactionJournalStorage.loadLatest(
                    server, player.getUUID().toString());
            // Never emit inventories or raw SNBT in chat.
            return new Result(true, "Transaction " + journal.journalId
                    + " | phase: " + journal.phase
                    + " | source: " + journal.source.bounds()
                    + " | target: " + journal.target.bounds()
                    + " | protected cells: "
                    + (journal.source.before.size() + journal.target.before.size())
                    + " | block entities: " + journal.blockEntityCount
                    + " | checksum: " + journal.sha256.substring(0, 12)
                    + " | execution/rollback not enabled");
        } catch (IOException error) {
            return new Result(false, "No valid prepared transaction: " + error.getMessage());
        }
    }

    private static TransactionJournal.Region region(int minX, int minY, int minZ,
                                                    int maxX, int maxY, int maxZ) {
        TransactionJournal.Region result = new TransactionJournal.Region();
        result.minX = minX;
        result.minY = minY;
        result.minZ = minZ;
        result.maxX = maxX;
        result.maxY = maxY;
        result.maxZ = maxZ;
        return result;
    }

    private static void captureRegion(ServerLevel level, TransactionJournal.Region region,
                                      TransactionJournal journal, boolean requireEmpty)
            throws CaptureRejected {
        for (int x = region.minX; x <= region.maxX; x++) {
            for (int y = region.minY; y <= region.maxY; y++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos))
                        throw new CaptureRejected("A region includes an unloaded chunk");
                    BlockState state = level.getBlockState(pos);
                    BlockEntity blockEntity = level.getBlockEntity(pos);
                    if (requireEmpty && (!state.isAir() || blockEntity != null))
                        throw new CaptureRejected("Destination volume has occupied cells");

                    String nbt = null;
                    if (blockEntity != null) {
                        try {
                            nbt = blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
                        } catch (RuntimeException failure) {
                            throw new CaptureRejected("Unable to serialize a block entity");
                        }
                        int bytes = nbt.getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > MAX_SINGLE_NBT_BYTES
                                || (long) journal.totalNbtBytes + bytes > TransactionJournal.MAX_NBT_BYTES)
                            throw new CaptureRejected("Block-entity NBT exceeds the capture budget");
                        journal.blockEntityCount++;
                        journal.totalNbtBytes += bytes;
                    }
                    region.before.add(new TransactionJournal.Cell(
                            x, y, z,
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                            state.toString(), nbt
                    ));
                }
            }
        }
    }

    private static final class CaptureRejected extends Exception {
        private CaptureRejected(String message) { super(message); }
    }

    public record Result(boolean success, String message) { }
}
