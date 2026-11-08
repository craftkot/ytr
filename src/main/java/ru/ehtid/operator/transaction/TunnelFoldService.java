package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.storage.RelocationExecutionStorage;
import ru.ehtid.operator.storage.RelocationWatchdogStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Minimal autonomous spatial interference. Move one ordinary STONE block sideways
 * into an air cell at least two blocks above a previously mapped walkway.
 * The player has to have moved away and be facing in the opposite direction.
 * Every actual write is guarded by the existing durable relocation WAL.
 */
public final class TunnelFoldService {
    public static final String MARKER = "AUTO_TUNNEL_FOLD_V1";
    public static final String MARKER_V2 = "AUTO_TUNNEL_FOLD_V2";
    private static final int MAX_CANDIDATES = 192;

    private TunnelFoldService() { }

    public static TransactionJournal prepare(MinecraftServer server, ServerPlayer player,
                                             PlayerMemory memory, long tick) {
        if (memory.tunnelCells == null || memory.tunnelCells.size() < 64
                || player.isSpectator() || player.isCreative()) return null;
        try {
            if (RelocationWatchdogStorage.scan(server).blocksNewWrites()
                    || hasPriorFold(server, player.getUUID())) return null;
            ServerLevel level = (ServerLevel) player.level();
            String dimension = level.dimension().toString();
            List<PlayerMemory.TunnelCell> cells = new ArrayList<>(memory.tunnelCells.values());
            int checked = 0;
            // An air scan can append hundreds of observed-air entries after a
            // mined tunnel. Count MINED candidates, not arbitrary map entries:
            // otherwise the autonomous planner silently starves itself.
            for (int i = cells.size() - 1; i >= 0 && checked < MAX_CANDIDATES; i--) {
                PlayerMemory.TunnelCell cell = cells.get(i);
                if (cell == null || !PlayerMemory.TUNNEL_SOURCE_MINED.equals(cell.source))
                    continue;
                checked++;
                if (!dimension.equals(cell.dimension)
                        || cell.y < level.getMinY() + 5 || cell.y >= level.getMaxY() - 5)
                    continue;
                double dx = cell.x + 0.5D - player.getX();
                double dz = cell.z + 0.5D - player.getZ();
                double distanceSq = dx * dx + dz * dz;
                if (distanceSq < 144.0D || distanceSq > 900.0D) continue;
                Vec3 look = player.getLookAngle();
                double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
                if (lookLength < 0.3D
                        || (dx * look.x + dz * look.z)
                            / Math.sqrt(distanceSq) / lookLength > -0.45D) continue;

                BlockPos feet = new BlockPos(cell.x, cell.y, cell.z);
                BlockPos head = feet.above();
                BlockPos target = feet.above(2);
                BlockPos floor = feet.below();
                if (!loaded(level, feet, head, target, floor)
                        || !isAir(level, feet) || !isAir(level, head)
                        || !isAir(level, target) || !isStone(level, floor)) continue;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        cell.x, cell.z);
                if (cell.y > surface - 8) continue;
                for (int[] axis : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    BlockPos source = target.offset(axis[0], 0, axis[1]);
                    if (!loaded(level, source) || !isStone(level, source)
                            || !areaUnoccupied(server, level, source, target)) continue;
                    // Prefer a two-cell strip running perpendicular to the
                    // one-block displacement, with 4 durable WAL writes.
                    BlockPos along = axis[0] != 0
                            ? new BlockPos(0, 0, 1) : new BlockPos(1, 0, 0);
                    BlockPos source2 = source.offset(along);
                    BlockPos target2 = target.offset(along);
                    if (loaded(level, source2, target2)
                            && isStone(level, source2) && isAir(level, target2)
                            && areaUnoccupied(server, level,
                                    source, source2, target, target2)) {
                        TransactionJournal wide = twoCellJournal(player.getUUID(), dimension,
                                source, target, tick);
                        if (safeCandidate(level, wide)) {
                            TransactionJournalStorage.save(server, wide);
                            return wide;
                        }
                    }
                    // Keep V1 fallback where a second block is not safe.
                    TransactionJournal journal = oneCellJournal(player.getUUID(), dimension,
                            source, target, tick);
                    if (!safeCandidate(level, journal)) continue;
                    TransactionJournalStorage.save(server, journal);
                    return journal;
                }
            }
        } catch (IOException | RuntimeException error) {
            OperatorMod.LOGGER.warn("Autonomous tunnel fold preparation stopped: {}", error.toString());
        }
        return null;
    }

    private static boolean safeCandidate(ServerLevel level, TransactionJournal journal) {
        return "NO_LISTED_HAZARDS".equals(
                RecoveryBoundaryInspector.inspect(level, journal).status())
                && RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level))
                        .status() == RecoveryPlanner.Status.UNCHANGED;
    }

    /** Two contiguous upper-wall blocks, shifted one cell into empty air. */
    static TransactionJournal twoCellJournal(UUID owner, String dimension,
                                             BlockPos source, BlockPos target, long tick) {
        boolean alongX = source.getX() != target.getX();
        BlockPos stride = alongX ? new BlockPos(0, 0, 1) : new BlockPos(1, 0, 0);
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = UUID.randomUUID().toString();
        journal.ownerUuid = owner.toString();
        journal.sourceSnapshotId = MARKER_V2;
        journal.sourceSnapshotSha256 = MARKER_V2;
        journal.dimension = dimension;
        journal.capturedAtTick = tick;
        journal.offsetX = target.getX() - source.getX();
        journal.offsetY = target.getY() - source.getY();
        journal.offsetZ = target.getZ() - source.getZ();
        journal.source = pair(source, source.offset(stride), "minecraft:stone");
        journal.target = pair(target, target.offset(stride), "minecraft:air");
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }

    private static TransactionJournal.Region pair(BlockPos a, BlockPos b, String blockId) {
        TransactionJournal.Region region = new TransactionJournal.Region();
        region.minX = Math.min(a.getX(), b.getX());
        region.minY = Math.min(a.getY(), b.getY());
        region.minZ = Math.min(a.getZ(), b.getZ());
        region.maxX = Math.max(a.getX(), b.getX());
        region.maxY = Math.max(a.getY(), b.getY());
        region.maxZ = Math.max(a.getZ(), b.getZ());
        region.before = new ArrayList<>(List.of(
                new TransactionJournal.Cell(a.getX(), a.getY(), a.getZ(), blockId,
                        "Block{" + blockId + "}", null),
                new TransactionJournal.Cell(b.getX(), b.getY(), b.getZ(), blockId,
                        "Block{" + blockId + "}", null)
        ));
        return region;
    }

    static TransactionJournal oneCellJournal(UUID owner, String dimension,
                                             BlockPos source, BlockPos target, long tick) {
        TransactionJournal journal = new TransactionJournal();
        journal.journalId = UUID.randomUUID().toString();
        journal.ownerUuid = owner.toString();
        journal.sourceSnapshotId = MARKER;
        journal.sourceSnapshotSha256 = MARKER;
        journal.dimension = dimension;
        journal.capturedAtTick = tick;
        journal.offsetX = target.getX() - source.getX();
        journal.offsetY = target.getY() - source.getY();
        journal.offsetZ = target.getZ() - source.getZ();
        journal.source = oneCell(source, "minecraft:stone");
        journal.target = oneCell(target, "minecraft:air");
        journal.sha256 = TransactionIntegrity.fingerprint(journal);
        return journal;
    }

    private static TransactionJournal.Region oneCell(BlockPos at, String blockId) {
        TransactionJournal.Region region = new TransactionJournal.Region();
        region.minX = region.maxX = at.getX();
        region.minY = region.maxY = at.getY();
        region.minZ = region.maxZ = at.getZ();
        region.before = new ArrayList<>(List.of(new TransactionJournal.Cell(
                at.getX(), at.getY(), at.getZ(), blockId, "Block{" + blockId + "}", null)));
        return region;
    }

    public static String apply(MinecraftServer server, ServerPlayer player,
                               TransactionJournal journal) {
        if (!SandboxTransactionEngine.isTunnelFold(journal))
            return "Tunnel fold REFUSED: malformed journal";
        try {
            if (RelocationWatchdogStorage.scan(server).blocksNewWrites())
                return "Tunnel fold REFUSED: server-wide WAL interlock";
            if (!player.level().dimension().toString().equals(journal.dimension))
                return "Tunnel fold REFUSED: player changed dimension";
            ServerLevel level = (ServerLevel) player.level();
            BlockPos source = new BlockPos(journal.source.minX, journal.source.minY, journal.source.minZ);
            BlockPos target = new BlockPos(journal.target.minX, journal.target.minY, journal.target.minZ);
            if (!loaded(level, source, target,
                        new BlockPos(journal.source.maxX, journal.source.maxY, journal.source.maxZ),
                        new BlockPos(journal.target.maxX, journal.target.maxY, journal.target.maxZ))
                    || !areaUnoccupied(server, level, source, target,
                        new BlockPos(journal.source.maxX, journal.source.maxY, journal.source.maxZ),
                        new BlockPos(journal.target.maxX, journal.target.maxY, journal.target.maxZ))
                    || !"NO_LISTED_HAZARDS".equals(
                            RecoveryBoundaryInspector.inspect(level, journal).status()))
                return "Tunnel fold REFUSED: unsafe or occupied region";
            var result = SandboxTransactionEngine.applyTunnelFold(journal,
                    new MinecraftSafeBlockWorld(level), level::getGameTime, ledgerStore(server));
            return "Tunnel fold " + result.outcome() + ": " + result.reason()
                    + " | durable journal " + journal.journalId;
        } catch (IOException | RuntimeException error) {
            return "Tunnel fold REFUSED: " + error.getMessage();
        }
    }

    public static String status(MinecraftServer server, ServerPlayer player, String id) {
        try {
            TransactionJournal journal = TransactionJournalStorage.load(
                    server, player.getUUID().toString(), id);
            if (!SandboxTransactionEngine.isTunnelFold(journal))
                return "invalid autonomous fold journal";
            if (!RelocationExecutionStorage.exists(server, journal))
                return "PREPARED (no block writes started)";
            var state = RelocationExecutionStorage.load(server, journal);
            return state.phase + " | apply=" + state.appliedWrites + "/" + state.totalWrites
                    + " | restore=" + state.restoredWrites;
        } catch (IOException | RuntimeException error) {
            return "journal/WAL error: " + error.getMessage();
        }
    }

    public static String rollback(MinecraftServer server, ServerPlayer player, String id) {
        try {
            TransactionJournal journal = TransactionJournalStorage.load(
                    server, player.getUUID().toString(), id);
            if (!SandboxTransactionEngine.isTunnelFold(journal)
                    || !player.level().dimension().toString().equals(journal.dimension))
                return "Tunnel fold rollback REFUSED: wrong dimension or journal";
            var state = RelocationExecutionStorage.load(server, journal);
            if ("ROLLED_BACK".equals(state.phase)) return "Tunnel fold already ROLLED_BACK";
            ServerLevel level = (ServerLevel) player.level();
            BlockPos source = new BlockPos(journal.source.minX, journal.source.minY, journal.source.minZ);
            BlockPos target = new BlockPos(journal.target.minX, journal.target.minY, journal.target.minZ);
            if (!loaded(level, source, target,
                        new BlockPos(journal.source.maxX, journal.source.maxY, journal.source.maxZ),
                        new BlockPos(journal.target.maxX, journal.target.maxY, journal.target.maxZ))
                    || !areaUnoccupied(server, level, source, target,
                        new BlockPos(journal.source.maxX, journal.source.maxY, journal.source.maxZ),
                        new BlockPos(journal.target.maxX, journal.target.maxY, journal.target.maxZ))
                    || !"NO_LISTED_HAZARDS".equals(
                            RecoveryBoundaryInspector.inspect(level, journal).status()))
                return "Tunnel fold rollback REFUSED: region unsafe or occupied";
            var result = SandboxTransactionEngine.rollback(journal, state,
                    new MinecraftSafeBlockWorld(level), level::getGameTime, ledgerStore(server));
            return "Tunnel fold rollback " + result.outcome() + ": " + result.reason();
        } catch (IOException | RuntimeException error) {
            return "Tunnel fold rollback REFUSED: " + error.getMessage();
        }
    }

    private static SandboxTransactionEngine.LedgerStore ledgerStore(MinecraftServer server) {
        return new SandboxTransactionEngine.LedgerStore() {
            @Override
            public void create(TransactionJournal j, RelocationExecutionLedger state)
                    throws IOException {
                RelocationExecutionStorage.create(server, j, state);
            }
            @Override
            public void update(TransactionJournal j, RelocationExecutionLedger state)
                    throws IOException {
                RelocationExecutionStorage.update(server, j, state);
            }
            @Override
            public void flushWorld() throws IOException {
                if (!server.saveEverything(true, true, true))
                    throw new IOException("Minecraft world flush returned false");
            }
        };
    }

    private static boolean loaded(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) if (!level.hasChunkAt(pos)) return false;
        return true;
    }

    private static boolean isAir(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos).getFluidState().isEmpty()
                && level.getBlockEntity(pos) == null;
    }

    private static boolean isStone(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.STONE)
                && level.getBlockEntity(pos) == null;
    }

    private static boolean areaUnoccupied(MinecraftServer server, ServerLevel level,
                                          BlockPos... positions) {
        if (positions.length < 2) return false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        AABB region = new AABB(minX, minY, minZ,
                maxX + 1.0D, maxY + 1.0D, maxZ + 1.0D).inflate(3.0D);
        if (!level.getEntities(null, region).isEmpty()) return false;
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.level() == level && region.contains(other.position())) return false;
        }
        return true;
    }

    /** Prevent a repeat even when a hard crash lost the player-memory marker. */
    private static boolean hasPriorFold(MinecraftServer server, UUID owner) throws IOException {
        Path folder = server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions").resolve(owner.toString());
        if (!Files.exists(folder)) return false;
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder))
            throw new IOException("Unsafe journal directory");
        try (Stream<Path> entries = Files.list(folder)) {
            // A truncated scan could miss a prior V1/V2 fold after a crash.
            // Refuse a new operation if the entire bounded owner directory
            // cannot be inspected. Never infer safety from only 16 files.
            List<Path> files = entries.limit(129).toList();
            if (files.size() > 128) throw new IOException(
                    "Too many owner journals to prove one-shot safety");
            for (Path file : files) {
                if (!file.getFileName().toString().endsWith(".json")) continue;
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - 5);
                TransactionJournal journal = TransactionJournalStorage.load(
                        server, owner.toString(), id);
                if (MARKER.equals(journal.sourceSnapshotId)
                        || MARKER_V2.equals(journal.sourceSnapshotId)) return true;
            }
        }
        return false;
    }
}
