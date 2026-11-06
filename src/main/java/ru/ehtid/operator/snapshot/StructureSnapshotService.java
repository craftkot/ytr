package ru.ehtid.operator.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import ru.ehtid.operator.analysis.StructureSnapshotPlanner;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.storage.StructureSnapshotStorage;

/**
 * Opt-in read-only capture and preflight. It does NOT set or break blocks.
 * NBT contents are saved to server-only files and NEVER included in command output.
 */
public final class StructureSnapshotService {
    public static final int MAX_BLOCK_ENTITY_SNBT_BYTES = 256 * 1024;
    public static final int MAX_TOTAL_NBT_BYTES = 2 * 1024 * 1024;

    private StructureSnapshotService() { }

    public static Result capture(MinecraftServer server, ServerPlayer player,
                                 PlayerMemory memory) {
        if (memory == null) return new Result(false, "No recorded construction memory");
        ServerLevel level = (ServerLevel) player.level();
        String dimension = level.dimension().toString();
        BlockPos playerPos = player.blockPosition();
        StructureSnapshotPlanner.Selection chosen = StructureSnapshotPlanner.select(
                memory.constructionBlocks.values(), dimension,
                playerPos.getX(), playerPos.getY(), playerPos.getZ());
        if (chosen == null) {
            return new Result(false, "No connected building candidate within "
                    + StructureSnapshotPlanner.MAX_ANCHOR_DISTANCE + " blocks");
        }
        if (chosen.oversized()) {
            return new Result(false, "Nearest building exceeds the "
                    + StructureSnapshotPlanner.MAX_CAPTURE_BLOCKS + "-block capture limit");
        }

        StructureSnapshot snapshot = new StructureSnapshot();
        snapshot.snapshotId = UUID.randomUUID().toString();
        snapshot.playerUuid = player.getUUID().toString();
        snapshot.dimension = dimension;
        snapshot.capturedAtTick = level.getGameTime();
        snapshot.minX = chosen.minX();
        snapshot.minY = chosen.minY();
        snapshot.minZ = chosen.minZ();
        snapshot.maxX = chosen.maxX();
        snapshot.maxY = chosen.maxY();
        snapshot.maxZ = chosen.maxZ();

        for (PlayerMemory.ConstructionBlock block : chosen.blocks()) {
            BlockPos pos = new BlockPos(block.x, block.y, block.z);
            if (!level.hasChunkAt(pos)) {
                return new Result(false, "Capture aborted: source spans an unloaded chunk");
            }
            BlockState actual = level.getBlockState(pos);
            String currentId = BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
            if (!block.blockId.equals(currentId)) {
                return new Result(false, "Capture aborted: construction memory differs from world; retry after observer audit");
            }

            String nbt = null;
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                try {
                    nbt = blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
                } catch (RuntimeException serializationFailure) {
                    return new Result(false, "Capture aborted: a block entity cannot be serialized safely");
                }
                int bytes = nbt.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_BLOCK_ENTITY_SNBT_BYTES
                        || (long) snapshot.blockEntitySnbtBytes + bytes > MAX_TOTAL_NBT_BYTES) {
                    return new Result(false, "Capture aborted: block-entity data exceeds snapshot size limits");
                }
                snapshot.blockEntitySnbtBytes += bytes;
                snapshot.blockEntityCount++;
            }
            snapshot.blocks.add(new StructureSnapshot.Entry(
                    block.x, block.y, block.z, currentId, actual.toString(), nbt
            ));
        }

        snapshot.sha256 = SnapshotIntegrity.fingerprint(snapshot);
        try {
            Path file = StructureSnapshotStorage.save(server, snapshot);
            // Use only the final filename. Do not expose inventory NBT in the response.
            return new Result(true, "Captured " + snapshot.blockCount() + " blocks, "
                    + snapshot.blockEntityCount + " block entities"
                    + " | bounds " + snapshot.bounds()
                    + " | snapshot " + file.getFileName()
                    + " | no world changes");
        } catch (IOException error) {
            return new Result(false, "Snapshot was not saved: " + error.getMessage());
        }
    }

    public static Result preflight(MinecraftServer server, ServerPlayer player,
                                   int anchorX, int anchorY, int anchorZ) {
        StructureSnapshot snapshot;
        try {
            snapshot = StructureSnapshotStorage.loadLatest(
                    server, player.getUUID().toString());
        } catch (IOException error) {
            return new Result(false, "Unable to load saved snapshot: " + error.getMessage());
        }

        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().toString().equals(snapshot.dimension)) {
            return new Result(false, "Snapshot belongs to another dimension");
        }
        if (!SnapshotIntegrity.valid(snapshot)) {
            return new Result(false, "Snapshot checksum mismatch");
        }
        if (snapshot.blocks == null || snapshot.blocks.isEmpty()
                || snapshot.blocks.size() > StructureSnapshotPlanner.MAX_CAPTURE_BLOCKS) {
            return new Result(false, "Invalid or oversized snapshot");
        }

        List<PlayerMemory.ConstructionBlock> sourceBlocks = new ArrayList<>();
        for (StructureSnapshot.Entry entry : snapshot.blocks) {
            BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
            if (!level.hasChunkAt(pos)) {
                return new Result(false, "Preflight blocked: source chunk not loaded");
            }
            BlockState actual = level.getBlockState(pos);
            String currentId = BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString();
            if (!currentId.equals(entry.blockId())
                    || !actual.toString().equals(entry.blockState())) {
                return new Result(false, "Preflight blocked: source block state changed after snapshot");
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (entry.blockEntitySnbt() == null) {
                if (blockEntity != null) {
                    return new Result(false, "Preflight blocked: source gained a block entity");
                }
            } else {
                if (blockEntity == null) {
                    return new Result(false, "Preflight blocked: source lost a block entity");
                }
                String currentNbt;
                try {
                    currentNbt = blockEntity.saveWithFullMetadata(level.registryAccess()).toString();
                } catch (RuntimeException failure) {
                    return new Result(false, "Preflight blocked: block-entity verification failed");
                }
                if (!entry.blockEntitySnbt().equals(currentNbt)) {
                    return new Result(false, "Preflight blocked: block-entity data changed after snapshot");
                }
            }
            sourceBlocks.add(new PlayerMemory.ConstructionBlock(
                    entry.x(), entry.y(), entry.z(), snapshot.dimension,
                    entry.blockId(), entry.blockState(), snapshot.capturedAtTick
            ));
        }

        StructureSnapshotPlanner.Selection source = new StructureSnapshotPlanner.Selection(
                snapshot.dimension,
                snapshot.minX, snapshot.minY, snapshot.minZ,
                snapshot.maxX, snapshot.maxY, snapshot.maxZ,
                0L, false, sourceBlocks);
        var check = StructureSnapshotPlanner.translation(source, anchorX, anchorY, anchorZ,
                level.getMinY(), level.getMaxY(), new StructureSnapshotPlanner.DestinationInspector() {
                    @Override
                    public boolean loaded(int x, int y, int z) {
                        return level.hasChunkAt(new BlockPos(x, y, z));
                    }

                    @Override
                    public boolean empty(int x, int y, int z) {
                        return level.getBlockState(new BlockPos(x, y, z)).isAir();
                    }
                });
        return new Result(check.eligible(),
                "Preflight " + check.reason()
                        + " | checked " + check.checkedBlocks()
                        + " destination blocks | source: " + snapshot.blockCount()
                        + " blocks, NBT entries: " + snapshot.blockEntityCount
                        + " | no blocks were moved; this is not rollback-safe");
    }

    public record Result(boolean success, String message) { }
}
