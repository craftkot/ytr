package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.storage.RelocationWatchdogStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * FIRST bounded global-world field: attempts reversible, real block changes in
 * already loaded, presently unobserved cave chunks across multiple sectors.
 *
 * Does NOT force-load chunks, touch biomes, teleport players, or use generators.
 * Runs ONLY after explicit independent world-field opt-in and normal training.
 * At most 16 historic sectors and one operation per 1200 server ticks.
 *
 * Each event is an ordinary 2-cell/4-write WAL transaction, not a collection
 * of unjournaled block sets. The world-level history is persisted BEFORE apply.
 */
public final class WorldFieldService {
    public static final String MARKER = "AUTO_WORLD_FIELD_V1";
    private static final int MAX_OWNERS_AUDIT = 128;
    private static final int MAX_OWNER_ENTRIES_AUDIT = 128;
    private static final int MAX_CANDIDATE_COLUMNS = 32;

    public record Candidate(TransactionJournal journal, String sector) { }

    private WorldFieldService() { }

    public static Candidate prepare(MinecraftServer server, ServerPlayer actor,
                                    OperatorWorldData data, long tick) {
        if (server == null || actor == null || data == null
                || !data.worldFieldEnabled || !data.autonomousAnomaliesEnabled
                || actor.isSpectator() || actor.isCreative()
                || data.worldFieldHistory == null
                || data.worldFieldHistory.size() >= WorldFieldPolicy.MAX_SECTORS
                || tick < data.lastWorldFieldTick
                || tick - data.lastWorldFieldTick < WorldFieldPolicy.COOLDOWN_TICKS)
            return null;
        try {
            if (RelocationWatchdogStorage.scan(server).blocksNewWrites()
                    || !historyMatchesDisk(server, data.worldFieldHistory))
                return null;
            ServerLevel level = (ServerLevel) actor.level();
            // Sample a finite ring of preloaded chunks. The world stretches
            // across explored space as the player moves; never generate distant
            // chunks or scan an entire map in one tick.
            int x0 = actor.blockPosition().getX();
            int z0 = actor.blockPosition().getZ();
            int checked = 0;
            for (int radius : new int[]{128, 144, 112}) {
                for (int[] axis : new int[][]{
                        {0, -1}, {1, 0}, {-1, 0}, {0, 1},
                        {1, -1}, {-1, -1}, {1, 1}, {-1, 1}
                }) {
                    if (++checked > MAX_CANDIDATE_COLUMNS) return null;
                    int x = x0 + axis[0] * radius;
                    int z = z0 + axis[1] * radius;
                    String sector = WorldFieldPolicy.sector(level.dimension().toString(), x, z);
                    if (!WorldFieldPolicy.maySchedule(
                            data.worldFieldHistory, sector, tick, data.lastWorldFieldTick))
                        continue;
                    BlockPos top = new BlockPos(x, actor.blockPosition().getY(), z);
                    if (!level.hasChunkAt(top)
                            || !nobodyNear(server, level, top)) continue;
                    int surface = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    int bottom = Math.max(level.getMinY() + 5, surface - 80);
                    // Deliberate bounded vertical scan: skipping 8 Y-levels
                    // made narrow real caves almost impossible to select.
                    int testedHeights = 0;
                    for (int y = surface - 8; y >= bottom && testedHeights++ < 64; y--) {
                        Candidate candidate = prepareAt(server, actor,
                                new BlockPos(x, y, z), data.worldFieldHistory);
                        if (candidate != null) return candidate;
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            OperatorMod.LOGGER.warn("World field planning rejected: {}", error.toString());
        }
        return null;
    }

    /**
     * The same production geometry and observer gate for normal distributed
     * scheduling and a CI-directed test fixture. No journal is saved here.
     */
    static Candidate prepareAt(MinecraftServer server, ServerPlayer actor,
                               BlockPos target, Map<String, String> history) {
        if (server == null || actor == null || target == null || history == null)
            return null;
        ServerLevel level = (ServerLevel) actor.level();
        String dimension = level.dimension().toString();
        String sector = WorldFieldPolicy.sector(dimension, target.getX(), target.getZ());
        if (history.containsKey(sector)
                || history.size() >= WorldFieldPolicy.MAX_SECTORS
                || target.getY() < level.getMinY() + 5
                || target.getY() >= level.getMaxY() - 5
                || !nobodyNear(server, level, target)) return null;
        BlockPos feet = target.below(2);
        if (!loaded(level, feet, feet.above(), target, feet.below())
                || !empty(level, feet) || !empty(level, feet.above())
                || !empty(level, target) || !stone(level, feet.below())) return null;
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getX(), target.getZ());
        if (target.getY() > surface - 8) return null;

        for (int[] axis : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos source = target.offset(axis[0], 0, axis[1]);
            BlockPos stride = axis[0] != 0
                    ? new BlockPos(0, 0, 1) : new BlockPos(1, 0, 0);
            BlockPos source2 = source.offset(stride);
            BlockPos target2 = target.offset(stride);
            if (!loaded(level, source, source2, target2)
                    || !stone(level, source) || !stone(level, source2)
                    || !empty(level, target2)
                    || !nobodyNear(server, level, source)
                    || !nobodyNear(server, level, target2)
                    || !vacant(level, source, source2, target, target2)
                    || !hiddenBySolidTerrain(server, level,
                            source, source2, target, target2)) continue;

            TransactionJournal journal = TunnelFoldService.twoCellJournal(
                    actor.getUUID(), dimension, source, target, level.getGameTime());
            journal.sourceSnapshotId = MARKER;
            journal.sourceSnapshotSha256 = MARKER;
            journal.sha256 = TransactionIntegrity.fingerprint(journal);
            if (SandboxTransactionEngine.isTunnelFold(journal)
                    && "NO_LISTED_HAZARDS".equals(
                            RecoveryBoundaryInspector.inspect(level, journal).status())
                    && RecoveryPlanner.inspect(journal, new MinecraftSafeBlockWorld(level))
                            .status() == RecoveryPlanner.Status.UNCHANGED)
                return new Candidate(journal, sector);
        }
        return null;
    }

    public static boolean nobodyNear(MinecraftServer server, ServerLevel level,
                                     BlockPos position) {
        List<WorldFieldPolicy.Observer> observers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            observers.add(new WorldFieldPolicy.Observer(
                    player.level().dimension().toString(), player.getX(), player.getZ()));
        }
        // Synthetic dedicated-server smoke has no connected actor; the
        // production caller must still be separately checked before writes.
        return WorldFieldPolicy.nobodyNear(level.dimension().toString(),
                position.getX(), position.getZ(), observers);
    }

    public static boolean nobodyNearJournal(MinecraftServer server, ServerLevel level,
                                            TransactionJournal journal) {
        BlockPos source = new BlockPos(
                journal.source.minX, journal.source.minY, journal.source.minZ);
        BlockPos source2 = new BlockPos(
                journal.source.maxX, journal.source.maxY, journal.source.maxZ);
        BlockPos target = new BlockPos(
                journal.target.minX, journal.target.minY, journal.target.minZ);
        BlockPos target2 = new BlockPos(
                journal.target.maxX, journal.target.maxY, journal.target.maxZ);
        return nobodyNear(server, level, source)
                && nobodyNear(server, level, source2)
                && nobodyNear(server, level, target)
                && nobodyNear(server, level, target2)
                && hiddenBySolidTerrain(server, level, source, source2, target, target2);
    }

    /**
     * At the exact transaction boundary, require actual opaque terrain
     * between EVERY player and EVERY changed block. A distance-only gate is
     * insufficient when a cave corridor is visible from far away.
     * First preflight all ray chunks to avoid causing chunk loads ourselves.
     * This still cannot detect remote cameras, maps or external spectator mods.
     */
    private static boolean hiddenBySolidTerrain(MinecraftServer server,
                                                 ServerLevel level,
                                                 BlockPos... changedBlocks) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level) continue;
            Vec3 eye = player.getEyePosition();
            for (BlockPos block : changedBlocks) {
                Vec3 point = new Vec3(
                        block.getX() + 0.5D, block.getY() + 0.5D, block.getZ() + 0.5D);
                double length = eye.distanceTo(point);
                if (length <= 2D) return false;
                // More than 32 chunks away, outside the supported sight
                // budget. Do not reject every event merely because another
                // player is on the opposite side of a multiplayer world.
                if (length > 512D) continue;
                int samples = Math.max(1, (int) Math.ceil(length / 4D));
                for (int i = 0; i <= samples; i++) {
                    double t = (double) i / samples;
                    BlockPos sample = new BlockPos(
                            (int) Math.floor(eye.x + (point.x - eye.x) * t),
                            (int) Math.floor(eye.y + (point.y - eye.y) * t),
                            (int) Math.floor(eye.z + (point.z - eye.z) * t));
                    if (!level.hasChunkAt(sample)) return false;
                }
                BlockHitResult hit = level.clip(new ClipContext(
                        eye, point, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player));
                // The changed source block itself is NOT an occluder.
                // Require a separate obstruction at least two blocks closer
                // to the player than the target, avoiding edge ambiguity.
                if (hit.getType() != HitResult.Type.BLOCK
                        || hit.getBlockPos().equals(block)
                        || !level.getBlockState(hit.getBlockPos()).canOcclude()
                        || Math.sqrt(eye.distanceToSqr(hit.getLocation())) > length - 2D)
                    return false;
            }
        }
        return true;
    }

    private static boolean vacant(ServerLevel level, BlockPos... positions) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos position : positions) {
            minX = Math.min(minX, position.getX()); minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ()); maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY()); maxZ = Math.max(maxZ, position.getZ());
        }
        return level.getEntities(null, new AABB(
                minX, minY, minZ, maxX + 1D, maxY + 1D, maxZ + 1D).inflate(3D)).isEmpty();
    }

    private static boolean loaded(ServerLevel level, BlockPos... positions) {
        for (BlockPos pos : positions) if (!level.hasChunkAt(pos)) return false;
        return true;
    }

    private static boolean empty(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockEntity(pos) == null
                && level.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean stone(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.STONE)
                && level.getBlockEntity(pos) == null;
    }

    /**
     * Crash-gap safety: journal is persisted before world history; if the
     * memory save was interrupted, the orphan PREPARED field journal must
     * block all later field operations rather than silently duplicate a sector.
     */
    public static boolean historyMatchesDisk(MinecraftServer server,
                                             Map<String, String> history) throws IOException {
        if (history == null || history.size() > WorldFieldPolicy.MAX_SECTORS)
            return false;
        Path root = server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality").resolve("transactions");
        if (!Files.exists(root)) return history.isEmpty();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root))
            throw new IOException("unsafe world field journal root");
        Map<String, String> discovered = new java.util.HashMap<>();
        try (Stream<Path> folders = Files.list(root)) {
            List<Path> owners = folders.limit(MAX_OWNERS_AUDIT + 1).toList();
            if (owners.size() > MAX_OWNERS_AUDIT)
                throw new IOException("world field owner audit limit exceeded");
            for (Path dir : owners) {
                if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir))
                    throw new IOException("unsafe owner directory in field audit");
                String owner = dir.getFileName().toString();
                try {
                    if (!UUID.fromString(owner).toString().equals(owner))
                        throw new IllegalArgumentException("noncanonical owner");
                } catch (IllegalArgumentException error) {
                    throw new IOException("unexpected journal owner", error);
                }
                try (Stream<Path> entries = Files.list(dir)) {
                    List<Path> files = entries.limit(MAX_OWNER_ENTRIES_AUDIT + 1).toList();
                    if (files.size() > MAX_OWNER_ENTRIES_AUDIT)
                        throw new IOException("owner journal audit limit exceeded");
                    for (Path file : files) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".tmp")) continue;
                        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                                || !name.endsWith(".json"))
                            throw new IOException("unsafe journal file in field audit");
                        String id = name.substring(0, name.length() - 5);
                        TransactionJournal journal = TransactionJournalStorage.load(server, owner, id);
                        if (!MARKER.equals(journal.sourceSnapshotId)) continue;
                        if (!SandboxTransactionEngine.isTunnelFold(journal))
                            throw new IOException("invalid field journal");
                        String sector = WorldFieldPolicy.sector(
                                journal.dimension, journal.target.minX, journal.target.minZ);
                        String value = owner + "/" + id;
                        if (discovered.putIfAbsent(sector, value) != null)
                            throw new IOException("duplicated field sector on disk");
                    }
                }
            }
        }
        return discovered.equals(history);
    }
}
