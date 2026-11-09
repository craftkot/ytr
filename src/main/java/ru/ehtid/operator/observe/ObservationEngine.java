package ru.ehtid.operator.observe;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.ai.OperatorBrain;
import ru.ehtid.operator.analysis.TunnelAnalyzer;
import ru.ehtid.operator.analysis.TunnelNetworkAnalyzer;
import ru.ehtid.operator.analysis.Tunnel3DNetworkAnalyzer;
import ru.ehtid.operator.analysis.ConstructionAnalyzer;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.storage.OperatorStorage;
import ru.ehtid.operator.storage.TransactionJournalStorage;
import ru.ehtid.operator.snapshot.StructureSnapshotService;
import ru.ehtid.operator.transaction.TransactionPreparationService;
import ru.ehtid.operator.transaction.RecoveryAuditService;
import ru.ehtid.operator.transaction.ExperimentalRelocationService;
import ru.ehtid.operator.transaction.TunnelFoldService;
import ru.ehtid.operator.transaction.WorldFieldService;
import ru.ehtid.operator.transaction.WorldFieldPolicy;
import ru.ehtid.operator.transaction.DeferredChunkPlan;
import ru.ehtid.operator.transaction.DeferredWorldFieldService;

public final class ObservationEngine {
    public static final long OBSERVATION_TICKS = 24_000L;
    private static final long MOVEMENT_SAMPLE_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 600L;
    private static final int MAX_PENDING_VOXEL_CHECKS = 256;
    private static final int MAX_PENDING_PLACEMENT_CHECKS = 1024;
    private static final int MAX_CONSTRUCTION_ANALYSES_PER_TICK = 1;
    private static final long CONSTRUCTION_ANALYSIS_INTERVAL = 20L;
    private static final int MAX_CONSTRUCTION_BLOCK_AUDITS_PER_TICK = 64;
    private static final int MAX_TRACKED_VOXEL_AUDITS_PER_TICK = 128;
    private static final long AIR_SCAN_INTERVAL = 100L;
    private static final int AIR_SCAN_RADIUS = 12;
    private static final int AIR_SCAN_BELOW = 6;
    private static final int AIR_SCAN_ABOVE = 3;
    private static final int MAX_AIR_SCAN_VISITED = 4096;
    private static final int MAX_ACTIVE_AIR_SCANS = 16;
    private static final int MAX_AIR_SCAN_WORK_PER_TICK = 128;
    private static final int[][] AIR_SCAN_DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final Map<String, PendingVoxelCheck> pendingVoxelChecks = new LinkedHashMap<>();
    private final Map<String, PendingPlacementCheck> pendingPlacementChecks = new LinkedHashMap<>();
    private final Map<String, Long> lastAirScanAt = new HashMap<>();
    private final Map<String, AirScanSession> activeAirScans = new HashMap<>();
    private final ArrayDeque<AirScanSession> airScanQueue = new ArrayDeque<>();
    private int auditPlayerCursor = 0;
    private int auditCellCursor = 0;
    private int constructionAnalysisCursor = 0;
    private int constructionAuditPlayerCursor = 0;
    private int constructionAuditCellCursor = 0;
    private String constructionAuditPlayerKey = "";
    private List<Map.Entry<String, PlayerMemory.ConstructionBlock>> constructionAuditSnapshot = List.of();
    private String auditPlayerKey = "";
    private List<Map.Entry<String, PlayerMemory.TunnelCell>> auditSnapshot = List.of();
    private OperatorWorldData data = new OperatorWorldData();
    private boolean loaded = false;
    private boolean dirty = false;

    public void onServerStarted(MinecraftServer server) {
        data = OperatorStorage.load(server);
        loaded = true;
        long now = server.overworld().getGameTime();

        if (data.observationStartedAt < 0L || now < data.observationStartedAt) {
            data.observationStartedAt = now;
            data.observationComplete = false;
            dirty = true;
        }
        if (now < data.lastSavedAt) {
            data.lastSavedAt = now;
            dirty = true;
        }
        updateWorldObservationPhase(now);
        auditPlayerCursor = 0;
        auditCellCursor = 0;
        constructionAnalysisCursor = 0;
        constructionAuditPlayerCursor = 0;
        constructionAuditCellCursor = 0;
        constructionAuditPlayerKey = "";
        constructionAuditSnapshot = List.of();
        auditPlayerKey = "";
        auditSnapshot = List.of();

        if (dirty) save(server);
        OperatorMod.LOGGER.info(
                "Loaded Operator memory: {} player profiles; world phase={}; personal training is tracked per player.",
                data.players.size(), data.observationComplete ? "READY" : "OBSERVATION"
        );
    }

    public void onServerTick(MinecraftServer server) {
        if (!loaded) return;

        long now = server.overworld().getGameTime();
        updateWorldObservationPhase(now);
        processPendingVoxelChecks();
        processPendingPlacementChecks();
        auditTrackedVoxels(server);
        auditTrackedConstructionBlocks(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            PlayerMemory memory = getOrCreate(player, now);
            memory.recordActiveTick(now);

            if (now % MOVEMENT_SAMPLE_INTERVAL == 0L) {
                memory.recordMovement(
                        player.getX(), player.getY(), player.getZ(),
                        player.level().dimension().toString(), now
                );
            }
            maybeStartNearbyAirScan(player, memory, now);
            if (now % OperatorBrain.EVALUATION_INTERVAL_TICKS == 0L) {
                OperatorBrain.Decision decision = OperatorBrain.evaluate(
                        memory, data.observationComplete, now
                );
                if (OperatorBrain.apply(memory, decision, now)) dirty = true;
            }
            if (now % OperatorBrain.EVALUATION_INTERVAL_TICKS == 0L) {
                String result = attemptAutonomousFold(server, player, memory, now);
                if (result.startsWith("Tunnel fold")) {
                    OperatorMod.LOGGER.warn("Autonomous tunnel fold {} result: {}",
                            player.getUUID(), result);
                }
            }
            // Independent, world-scale offscreen scheduler. No forced chunk
            // loading, at most one event per world per 1200 ticks.
            if (now % OperatorBrain.EVALUATION_INTERVAL_TICKS == 0L
                    && data.worldFieldEnabled) {
                String deferred = attemptDeferredWorldField(server, player, memory, now);
                if (deferred.startsWith("Tunnel fold"))
                    OperatorMod.LOGGER.info("Deferred cross-chunk field {}: {}",
                            player.getUUID(), deferred);
            }
            if (now % WorldFieldPolicy.COOLDOWN_TICKS == 0L) {
                String drift = attemptWorldField(server, player, memory, now);
                if (drift.startsWith("Tunnel fold")) {
                    OperatorMod.LOGGER.info("World field event {}: {}", player.getUUID(), drift);
                } else if (data.worldFieldEnabled) {
                    scheduleDeferredWorldField(server, player, memory, now);
                }
            }
            dirty = true;
        }

        processAirScanSessions();
        processConstructionAnalyses(now);
        if (dirty && now - data.lastSavedAt >= SAVE_INTERVAL) save(server);
    }

    public void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator() || !loaded) return;

        long now = level.getGameTime();
        String dimension = level.dimension().toString();
        String positionKey = dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        pendingPlacementChecks.remove(positionKey);

        // A broken player-placed block must disappear from its owner's structure map,
        // even when somebody other than the original builder removed it.
        for (PlayerMemory profile : data.players.values()) {
            if (profile.removePlacedBlock(pos.getX(), pos.getY(), pos.getZ(), dimension, now)) {
                dirty = true;
            }
        }

        // Creative building/demolition affects construction memory but not survival mining statistics.
        if (!player.isCreative()) {
            PlayerMemory memory = getOrCreate(serverPlayer, now);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            memory.recordBrokenBlock(
                    pos.getX(), pos.getY(), pos.getZ(),
                    dimension, blockId,
                    isRockLike(blockId), isTunnelMaterial(blockId), now
            );
            dirty = true;
        }
    }

    /**
     * Observes the adjacent placement target before vanilla handles the interaction.
     * A block is recorded only after a one-tick-later world check confirms placement.
     */
    public InteractionResult onBlockUse(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator() || !loaded) {
            return InteractionResult.PASS;
        }

        BlockPos target = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
        String dimension = level.dimension().toString();
        String key = dimension + "|" + target.getX() + "," + target.getY() + "," + target.getZ();
        long now = level.getGameTime();

        if (serverPlayer.getItemInHand(hand).getItem() instanceof BlockItem
                && level.hasChunkAt(target) && level.getBlockState(target).isAir()) {
            getOrCreate(serverPlayer, now);
            // Snapshot which companion cells were air BEFORE use. A later match
            // with the same block is evidence for a door's upper half or a bed's head.
            String itemId = BuiltInRegistries.ITEM
                    .getKey(serverPlayer.getItemInHand(hand).getItem()).toString();
            List<BlockPos> secondaryAirTargets = new ArrayList<>();
            if (itemId.endsWith("_door")) {
                BlockPos above = target.above();
                if (level.hasChunkAt(above) && level.getBlockState(above).isAir()) {
                    secondaryAirTargets.add(above.immutable());
                }
            } else if (itemId.endsWith("_bed")) {
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    BlockPos neighbour = target.relative(direction);
                    if (level.hasChunkAt(neighbour) && level.getBlockState(neighbour).isAir()) {
                        secondaryAirTargets.add(neighbour.immutable());
                    }
                }
            }
            pendingPlacementChecks.remove(key);
            pendingPlacementChecks.put(key, new PendingPlacementCheck(
                    level, target, dimension, serverPlayer.getUUID().toString(), now + 1L,
                    List.copyOf(secondaryAirTargets)
            ));
            while (pendingPlacementChecks.size() > MAX_PENDING_PLACEMENT_CHECKS) {
                Iterator<String> iterator = pendingPlacementChecks.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }

        boolean trackedByAnyPlayer = data.players.values().stream()
                .anyMatch(memory -> memory.hasTunnelCell(
                        dimension, target.getX(), target.getY(), target.getZ()
                ));
        if (trackedByAnyPlayer) {
            pendingVoxelChecks.remove(key);
            pendingVoxelChecks.put(key, new PendingVoxelCheck(
                    level, target, dimension, now + 1L
            ));
            while (pendingVoxelChecks.size() > MAX_PENDING_VOXEL_CHECKS) {
                Iterator<String> iterator = pendingVoxelChecks.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
        return InteractionResult.PASS;
    }

    private void processPendingVoxelChecks() {
        processPendingVoxelChecks(false);
    }

    private void processPendingPlacementChecks() {
        processPendingPlacementChecks(false);
    }

    private void processPendingPlacementChecks(boolean force) {
        if (pendingPlacementChecks.isEmpty()) return;

        Iterator<Map.Entry<String, PendingPlacementCheck>> iterator =
                pendingPlacementChecks.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingPlacementCheck check = iterator.next().getValue();
            if (!force && check.level().getGameTime() < check.dueGameTick()) continue;
            iterator.remove();

            if (!check.level().hasChunkAt(check.position())) continue;
            BlockState placedState = check.level().getBlockState(check.position());
            if (placedState.isAir()) continue;

            PlayerMemory memory = data.players.get(check.playerKey());
            if (memory == null) continue;
            for (PlayerMemory profile : data.players.values()) {
                profile.removePlacedBlock(
                        check.position().getX(), check.position().getY(),
                        check.position().getZ(), check.dimension(), check.level().getGameTime()
                );
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(placedState.getBlock()).toString();
            if (memory.recordPlacedBlock(
                    check.position().getX(), check.position().getY(), check.position().getZ(),
                    check.dimension(), blockId, placedState.toString(), check.level().getGameTime()
            )) {
                dirty = true;
            }

            boolean isDoor = blockId.endsWith("_door");
            boolean isBed = blockId.endsWith("_bed");
            if (isDoor || isBed) {
                for (BlockPos companion : check.secondaryAirTargets()) {
                    if (!check.level().hasChunkAt(companion)) continue;
                    BlockState companionState = check.level().getBlockState(companion);
                    // Never attribute existing or unrelated neighbour blocks to the builder.
                    if (companionState.getBlock() != placedState.getBlock()) continue;
                    if (isDoor && !companion.equals(check.position().above())) continue;
                    // Both positions were air before use and now share the same block type.
                    for (PlayerMemory profile : data.players.values()) {
                        if (profile.removePlacedBlock(
                                companion.getX(), companion.getY(), companion.getZ(),
                                check.dimension(), check.level().getGameTime())) dirty = true;
                    }
                    if (memory.recordPlacedBlock(
                            companion.getX(), companion.getY(), companion.getZ(), check.dimension(),
                            blockId, companionState.toString(), check.level().getGameTime())) dirty = true;
                }
            }
        }
    }

    private void processPendingVoxelChecks(boolean force) {
        if (pendingVoxelChecks.isEmpty()) return;

        Iterator<Map.Entry<String, PendingVoxelCheck>> iterator = pendingVoxelChecks.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingVoxelCheck check = iterator.next().getValue();
            if (!force && check.level().getGameTime() < check.dueGameTick()) continue;
            iterator.remove();

            if (!check.level().getBlockState(check.position()).isAir()) {
                for (PlayerMemory memory : data.players.values()) {
                    if (memory.removeTunnelCell(
                            check.dimension(), check.position().getX(),
                            check.position().getY(), check.position().getZ()
                    )) {
                        dirty = true;
                    }
                }
            }
        }
    }

    /**
     * Round-robin verification of mapped cells. Only loaded chunks are inspected;
     * this never force-loads terrain. It catches replacements from players, pistons,
     * dispensers, fluids and other mods, not just ordinary right-click placement.
     */
    private void auditTrackedVoxels(MinecraftServer server) {
        if (data.players.isEmpty() || data.players.values().stream()
                .noneMatch(memory -> !memory.tunnelCells.isEmpty())) {
            auditPlayerCursor = 0;
            auditCellCursor = 0;
            auditPlayerKey = "";
            auditSnapshot = List.of();
            return;
        }

        List<Map.Entry<String, PlayerMemory>> profiles = new ArrayList<>(data.players.entrySet());
        if (auditPlayerCursor >= profiles.size()) {
            auditPlayerCursor = 0;
            auditPlayerKey = "";
            auditSnapshot = List.of();
            auditCellCursor = 0;
        }

        Map<String, ServerLevel> levels = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.put(level.dimension().toString(), level);
        }

        int budget = MAX_TRACKED_VOXEL_AUDITS_PER_TICK;
        int attempts = 0;
        int maxAttempts = profiles.size() + MAX_TRACKED_VOXEL_AUDITS_PER_TICK;
        while (budget > 0 && attempts < maxAttempts) {
            if (auditPlayerCursor >= profiles.size()) auditPlayerCursor = 0;
            Map.Entry<String, PlayerMemory> profile = profiles.get(auditPlayerCursor);

            if (!profile.getKey().equals(auditPlayerKey)) {
                auditPlayerKey = profile.getKey();
                auditSnapshot = new ArrayList<>(profile.getValue().tunnelCells.entrySet());
                auditCellCursor = 0;
            }

            if (auditCellCursor >= auditSnapshot.size()) {
                auditPlayerCursor = (auditPlayerCursor + 1) % profiles.size();
                auditPlayerKey = "";
                auditSnapshot = List.of();
                auditCellCursor = 0;
                attempts++;
                continue;
            }

            PlayerMemory.TunnelCell cell = auditSnapshot.get(auditCellCursor++).getValue();
            attempts++;
            budget--;
            if (cell == null || cell.dimension == null) continue;

            PlayerMemory owner = profile.getValue();
            if (!owner.hasTunnelCell(cell.dimension, cell.x, cell.y, cell.z)) continue;
            ServerLevel level = levels.get(cell.dimension);
            if (level == null) continue;

            BlockPos position = new BlockPos(cell.x, cell.y, cell.z);
            if (!level.hasChunkAt(position)) continue;
            if (!level.getBlockState(position).isAir()) {
                for (PlayerMemory memory : data.players.values()) {
                    if (memory.removeTunnelCell(cell.dimension, cell.x, cell.y, cell.z)) dirty = true;
                }
            }
        }
    }

    /**
     * Verifies a small round-robin slice of the construction map against already
     * loaded chunks. This removes stale entries after explosions, pistons and
     * external block replacement without forcing chunks to load.
     */
    private void auditTrackedConstructionBlocks(MinecraftServer server) {
        if (data.players.isEmpty() || data.players.values().stream()
                .noneMatch(memory -> !memory.constructionBlocks.isEmpty())) {
            constructionAuditPlayerCursor = 0;
            constructionAuditCellCursor = 0;
            constructionAuditPlayerKey = "";
            constructionAuditSnapshot = List.of();
            return;
        }

        List<Map.Entry<String, PlayerMemory>> profiles = new ArrayList<>(data.players.entrySet());
        if (constructionAuditPlayerCursor >= profiles.size()) {
            constructionAuditPlayerCursor = 0;
            constructionAuditPlayerKey = "";
            constructionAuditSnapshot = List.of();
            constructionAuditCellCursor = 0;
        }

        Map<String, ServerLevel> levels = new HashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.put(level.dimension().toString(), level);
        }

        int budget = MAX_CONSTRUCTION_BLOCK_AUDITS_PER_TICK;
        int attempts = 0;
        int maxAttempts = profiles.size() + MAX_CONSTRUCTION_BLOCK_AUDITS_PER_TICK;
        while (budget > 0 && attempts < maxAttempts) {
            if (constructionAuditPlayerCursor >= profiles.size()) constructionAuditPlayerCursor = 0;
            Map.Entry<String, PlayerMemory> profile = profiles.get(constructionAuditPlayerCursor);

            if (!profile.getKey().equals(constructionAuditPlayerKey)) {
                constructionAuditPlayerKey = profile.getKey();
                constructionAuditSnapshot =
                        new ArrayList<>(profile.getValue().constructionBlocks.entrySet());
                constructionAuditCellCursor = 0;
            }

            if (constructionAuditCellCursor >= constructionAuditSnapshot.size()) {
                constructionAuditPlayerCursor = (constructionAuditPlayerCursor + 1) % profiles.size();
                constructionAuditPlayerKey = "";
                constructionAuditSnapshot = List.of();
                constructionAuditCellCursor = 0;
                attempts++;
                continue;
            }

            PlayerMemory.ConstructionBlock cell =
                    constructionAuditSnapshot.get(constructionAuditCellCursor++).getValue();
            attempts++;
            budget--;
            if (cell == null || cell.dimension == null || cell.blockId == null) continue;

            PlayerMemory owner = profile.getValue();
            String cellKey = cell.dimension + "|" + cell.x + "," + cell.y + "," + cell.z;
            if (!owner.constructionBlocks.containsKey(cellKey)) continue;
            ServerLevel level = levels.get(cell.dimension);
            if (level == null) continue;

            BlockPos position = new BlockPos(cell.x, cell.y, cell.z);
            if (!level.hasChunkAt(position)) continue;
            BlockState actualState = level.getBlockState(position);
            String actualBlockId = BuiltInRegistries.BLOCK
                    .getKey(actualState.getBlock()).toString();
            if (!cell.blockId.equals(actualBlockId)) {
                if (owner.removePlacedBlock(cell.x, cell.y, cell.z,
                        cell.dimension, level.getGameTime())) dirty = true;
            } else if (owner.updatePlacedBlockState(
                    cell.x, cell.y, cell.z, cell.dimension,
                    actualState.toString(), level.getGameTime())) {
                dirty = true;
            }
        }
    }

    private void maybeStartNearbyAirScan(ServerPlayer player, PlayerMemory memory, long now) {
        String playerKey = player.getUUID().toString();
        if (activeAirScans.containsKey(playerKey)) return;
        Long lastScan = lastAirScanAt.get(playerKey);
        if (lastScan != null && now - lastScan < AIR_SCAN_INTERVAL) return;
        if (activeAirScans.size() >= MAX_ACTIVE_AIR_SCANS) return;
        lastAirScanAt.put(playerKey, now);
        if (memory.getTunnelCellCount() >= PlayerMemory.MAX_TUNNEL_CELLS
                && memory.getObservedAirCellCount() == 0) return;

        ServerLevel level = (ServerLevel) player.level();
        BlockPos feet = player.blockPosition();
        BlockPos head = new BlockPos(feet.getX(), feet.getY() + 1, feet.getZ());
        if (!level.hasChunkAt(feet) || !level.hasChunkAt(head)) return;
        if (!isEnclosedAir(level, feet) && !isEnclosedAir(level, head)) return;

        AirScanSession session = new AirScanSession(
                playerKey, level, memory, feet, level.dimension().toString()
        );
        session.enqueue(feet);
        session.enqueue(head);
        if (!session.isDone()) {
            activeAirScans.put(playerKey, session);
            airScanQueue.addLast(session);
        }
    }

    private static boolean isEnclosedAir(ServerLevel level, BlockPos position) {
        if (!level.hasChunkAt(position) || !level.getBlockState(position).isAir()) return false;
        int solidNeighbours = 0;
        for (int[] direction : AIR_SCAN_DIRECTIONS) {
            BlockPos neighbour = new BlockPos(
                    position.getX() + direction[0],
                    position.getY() + direction[1],
                    position.getZ() + direction[2]
            );
            if (level.hasChunkAt(neighbour)
                    && level.getBlockState(neighbour).isCollisionShapeFullBlock(level, neighbour)) {
                solidNeighbours++;
            }
        }
        if (solidNeighbours >= 2) return true;
        if (solidNeighbours == 0) return false;
        int localSurface = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, position.getX(), position.getZ()
        );
        return position.getY() <= localSurface - 6;
    }

    private void processConstructionAnalyses(long now) {
        if (data.players.isEmpty()) {
            constructionAnalysisCursor = 0;
            return;
        }

        List<PlayerMemory> profiles = new ArrayList<>(data.players.values());
        if (constructionAnalysisCursor >= profiles.size()) constructionAnalysisCursor = 0;
        int analysed = 0;
        int attempts = 0;
        while (analysed < MAX_CONSTRUCTION_ANALYSES_PER_TICK && attempts < profiles.size()) {
            PlayerMemory memory = profiles.get(constructionAnalysisCursor);
            constructionAnalysisCursor = (constructionAnalysisCursor + 1) % profiles.size();
            attempts++;
            if (!memory.constructionAnalysisDirty) continue;
            if (memory.lastConstructionAnalysisAt >= 0L
                    && now >= memory.lastConstructionAnalysisAt
                    && now - memory.lastConstructionAnalysisAt < CONSTRUCTION_ANALYSIS_INTERVAL) continue;

            ConstructionAnalyzer.Analysis analysis =
                    ConstructionAnalyzer.analyze(memory.constructionBlocks.values());
            memory.updateConstructionSummary(
                    analysis.structureCount(), analysis.buildingCandidateCount(),
                    analysis.largestStructureBlockCount(), analysis.largestBuildingBlockCount(),
                    analysis.complexity(), now
            );
            ConstructionAnalyzer.Structure bestBuilding = analysis.structures().stream()
                    .filter(ConstructionAnalyzer.Structure::buildingCandidate)
                    .max(java.util.Comparator
                            .comparingInt(ConstructionAnalyzer.Structure::enclosedVoidCells)
                            .thenComparingInt(ConstructionAnalyzer.Structure::blockCount))
                    .orElse(null);
            int enclosedBuildings = (int) analysis.structures().stream()
                    .filter(ConstructionAnalyzer.Structure::enclosedBuildingCandidate).count();
            int roomCount = analysis.structures().stream().mapToInt(
                    ConstructionAnalyzer.Structure::roomCount).sum();
            int roomLinks = analysis.structures().stream().mapToInt(
                    ConstructionAnalyzer.Structure::doorLinkCount).sum();
            int furnishedRooms = analysis.structures().stream().mapToInt(
                    ConstructionAnalyzer.Structure::furnishedRoomCount).sum();
            int largestRoom = analysis.structures().stream()
                    .flatMap(structure -> structure.rooms().stream())
                    .mapToInt(room -> room.cells()).max().orElse(0);
            memory.updateRoomSummary(roomCount, roomLinks, furnishedRooms, largestRoom);
            memory.updateConstructionSemanticsSummary(
                    enclosedBuildings,
                    bestBuilding == null ? 0 : bestBuilding.enclosedVoidCells(),
                    bestBuilding == null ? 0 : bestBuilding.doorCount(),
                    bestBuilding == null ? 0 : bestBuilding.windowBlockCount(),
                    bestBuilding == null ? 0 : bestBuilding.stairBlockCount(),
                    bestBuilding == null ? 0 : bestBuilding.slabBlockCount(),
                    bestBuilding == null ? 0 : bestBuilding.floorLayerCount(),
                    bestBuilding == null ? 0.0D : bestBuilding.floorCoverage(),
                    bestBuilding == null ? 0.0D : bestBuilding.roofCoverage(),
                    bestBuilding == null ? 0.0D : bestBuilding.wallCoverage()
            );
            dirty = true;
            analysed++;
        }
    }

    private void processAirScanSessions() {
        int budget = MAX_AIR_SCAN_WORK_PER_TICK;
        while (budget > 0 && !airScanQueue.isEmpty()) {
            AirScanSession session = airScanQueue.removeFirst();
            session.processNext();
            budget--;
            if (session.isDone()) {
                activeAirScans.remove(session.playerKey, session);
                lastAirScanAt.put(session.playerKey, session.level.getGameTime());
            } else {
                airScanQueue.addLast(session);
            }
        }
    }

    /**
     * Bounded flood-fill of nearby connected air. It starts only from an enclosed
     * player position, stays within a 25x25x10 box, and never loads chunks.
     */
    private final class AirScanSession {
        private final String playerKey;
        private final ServerLevel level;
        private final PlayerMemory memory;
        private final String dimension;
        private final int minX, maxX, minY, maxY, minZ, maxZ;
        private final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        private final Set<BlockPos> visited = new HashSet<>();

        private AirScanSession(String playerKey, ServerLevel level, PlayerMemory memory,
                               BlockPos origin, String dimension) {
            this.playerKey = playerKey;
            this.level = level;
            this.memory = memory;
            this.dimension = dimension;
            this.minX = origin.getX() - AIR_SCAN_RADIUS;
            this.maxX = origin.getX() + AIR_SCAN_RADIUS;
            this.minY = Math.max(level.getMinY(), origin.getY() - AIR_SCAN_BELOW);
            this.maxY = Math.min(level.getMaxY(), origin.getY() + AIR_SCAN_ABOVE);
            this.minZ = origin.getZ() - AIR_SCAN_RADIUS;
            this.maxZ = origin.getZ() + AIR_SCAN_RADIUS;
        }

        private void enqueue(BlockPos position) {
            if (visited.size() >= MAX_AIR_SCAN_VISITED) return;
            if (position.getX() < minX || position.getX() > maxX
                    || position.getY() < minY || position.getY() > maxY
                    || position.getZ() < minZ || position.getZ() > maxZ) return;
            if (!level.hasChunkAt(position) || !visited.add(position)) return;
            frontier.addLast(position);
        }

        private void processNext() {
            BlockPos position = frontier.pollFirst();
            if (position == null || !level.hasChunkAt(position)) return;
            if (!level.getBlockState(position).isAir()) return;

            if (memory.recordDiscoveredAir(
                    position.getX(), position.getY(), position.getZ(), dimension, level.getGameTime()
            )) {
                dirty = true;
            }

            for (int[] direction : AIR_SCAN_DIRECTIONS) {
                enqueue(new BlockPos(
                        position.getX() + direction[0],
                        position.getY() + direction[1],
                        position.getZ() + direction[2]
                ));
            }
        }

        private boolean isDone() {
            // Once the visit cap is reached, finish processing the bounded frontier
            // instead of dropping already queued cells.
            return frontier.isEmpty();
        }
    }

    private record PendingVoxelCheck(
            Level level,
            BlockPos position,
            String dimension,
            long dueGameTick
    ) { }

    private record PendingPlacementCheck(
            Level level,
            BlockPos position,
            String dimension,
            String playerKey,
            long dueGameTick,
            List<BlockPos> secondaryAirTargets
    ) { }

    public void onServerStopping(MinecraftServer server) {
        if (loaded) {
            processPendingVoxelChecks(true);
            processPendingPlacementChecks(true);
            save(server);
        }
        pendingVoxelChecks.clear();
        pendingPlacementChecks.clear();
        lastAirScanAt.clear();
        activeAirScans.clear();
        airScanQueue.clear();
        auditSnapshot = List.of();
        auditPlayerKey = "";
        auditPlayerCursor = 0;
        auditCellCursor = 0;
        constructionAnalysisCursor = 0;
        constructionAuditPlayerCursor = 0;
        constructionAuditCellCursor = 0;
        constructionAuditPlayerKey = "";
        constructionAuditSnapshot = List.of();
        loaded = false;
    }

    public boolean isWorldObservationComplete() {
        return data.observationComplete;
    }

    /** Future mutation systems must pass both the world gate and the player's personal learning gate. */
    public boolean isPlayerReadyForOperator(ServerPlayer player) {
        if (!data.observationComplete) return false;
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        return memory != null && memory.personalObservationComplete(OBSERVATION_TICKS);
    }

    public String getStatus(MinecraftServer server) {
        if (!loaded) return "Operator data is not loaded.";
        long now = server.overworld().getGameTime();
        long elapsed = data.worldObservationElapsed(now);
        long remaining = Math.max(0L, OBSERVATION_TICKS - elapsed);
        long personallyTrained = data.players.values().stream()
                .filter(memory -> memory.personalObservationComplete(OBSERVATION_TICKS))
                .count();
        String phase = data.observationComplete ? "WORLD OBSERVATION COMPLETE" : "PASSIVE OBSERVATION";
        long plannedPlayers = data.players.values().stream()
                .filter(memory -> memory.plannedOperatorEvent != null
                        && !"NONE".equals(memory.plannedOperatorEvent))
                .count();
        long decisions = data.players.values().stream()
                .mapToLong(memory -> memory.operatorDecisionCount)
                .sum();
        int constructionBlocks = data.players.values().stream()
                .mapToInt(PlayerMemory::getConstructionBlockCount).sum();
        int buildingCandidates = data.players.values().stream()
                .mapToInt(memory -> memory.recognizedBuildingCount).sum();
        int enclosedBuildings = data.players.values().stream()
                .mapToInt(memory -> memory.recognizedEnclosedBuildingCount).sum();
        int roomCount = data.players.values().stream()
                .mapToInt(memory -> memory.recognizedRoomCount).sum();
        int roomLinks = data.players.values().stream()
                .mapToInt(memory -> memory.recognizedRoomConnections).sum();
        return "Operator 0.25 | " + phase
                + " | world learning: " + Math.min(elapsed, OBSERVATION_TICKS) + "/" + OBSERVATION_TICKS + " ticks"
                + " | remaining: " + remaining + " ticks"
                + " | player profiles: " + data.players.size()
                + " | individually trained: " + personallyTrained
                + " | mapped tunnel cells: " + data.players.values().stream().mapToInt(PlayerMemory::getTunnelCellCount).sum()
                + " | discovered air cells: " + data.players.values().stream().mapToInt(PlayerMemory::getObservedAirCellCount).sum()
                + " | nearby air-scan sessions: " + activeAirScans.size()
                + " | scan budget: " + MAX_AIR_SCAN_WORK_PER_TICK + "/tick"
                + " | loaded-cell audit: up to " + MAX_TRACKED_VOXEL_AUDITS_PER_TICK + "/tick"
                + " | construction audit: up to " + MAX_CONSTRUCTION_BLOCK_AUDITS_PER_TICK + "/tick"
                + " | brain plans: " + plannedPlayers
                + " | recorded decisions: " + decisions
                + " | player-placed blocks: " + constructionBlocks
                + " | building candidates: " + buildingCandidates
                + " | enclosed buildings: " + enclosedBuildings
                + " | enclosed rooms: " + roomCount
                + " | door connections: " + roomLinks
                + " | autonomous tunnel fold: " + (data.autonomousAnomaliesEnabled ? "ENABLED" : "DISABLED");
    }

    /**
     * Shared production/test execution gate. The test invokes THIS path on the
     * real server, not a mock world-write implementation. No relaxed gate in
     * production: the test modifies only its own marked disposable world.
     */
    private String attemptAutonomousFold(MinecraftServer server, ServerPlayer player,
                                         PlayerMemory memory, long now) {
        if (!data.autonomousAnomaliesEnabled) return "DISABLED";
        if (!data.observationComplete
                || !memory.personalObservationComplete(OBSERVATION_TICKS))
            return "OBSERVING";
        if (!"READY".equals(memory.operatorPhase)
                || !"TUNNEL_FOLD".equals(memory.plannedOperatorEvent))
            return "NO_TUNNEL_FOLD_PLAN";
        if (!memory.autonomousFoldJournalId.isEmpty()) return "ALREADY_ATTEMPTED";
        var journal = TunnelFoldService.prepare(server, player, memory, now);
        if (journal == null) return "NO_SAFE_CANDIDATE";
        memory.autonomousFoldJournalId = journal.journalId;
        // The one-shot identity must reach disk before even one block is changed.
        dirty = true;
        save(server);
        if (dirty) return "MEMORY_PERSIST_FAILED";
        return TunnelFoldService.apply(server, player, journal);
    }

    private void scheduleDeferredWorldField(MinecraftServer server,
                                             ServerPlayer player,
                                             PlayerMemory memory, long now) {
        if (!data.worldFieldEnabled || !data.autonomousAnomaliesEnabled
                || !data.observationComplete
                || !memory.personalObservationComplete(OBSERVATION_TICKS)) return;
        if (!deferredMetadataValid(now)) return;
        DeferredChunkPlan plan = DeferredWorldFieldService.stage(server, player, data, now);
        if (plan == null) return;
        data.worldFieldPending.put(plan.sector, plan);
        data.lastDeferredPlanTick = now;
        dirty = true;
        save(server);
        if (dirty) {
            // Failed persistence never authorizes a later write this session.
            data.worldFieldEnabled = false;
            OperatorMod.LOGGER.error("Could not persist deferred chunk plan. Field disabled.");
        } else {
            OperatorMod.LOGGER.info(
                    "Deferred chunk plan {} staged for sector {} at ({},{}) without loading terrain",
                    plan.planId, plan.sector, plan.targetX, plan.targetZ);
        }
    }

    private boolean deferredMetadataValid(long tick) {
        if (data.worldFieldPending == null
                || data.worldFieldHistory == null
                || data.worldFieldPending.size() > DeferredChunkPlan.MAX_PENDING)
            return false;
        for (var entry : data.worldFieldPending.entrySet()) {
            DeferredChunkPlan plan = entry.getValue();
            if (plan == null || !plan.valid() || !entry.getKey().equals(plan.sector)
                    || data.worldFieldHistory.containsKey(plan.sector)
                    || tick < plan.queuedAt)
                return false;
        }
        return true;
    }

    private String attemptDeferredWorldField(MinecraftServer server, ServerPlayer player,
                                              PlayerMemory memory, long now) {
        if (!data.worldFieldEnabled || !data.autonomousAnomaliesEnabled
                || !data.observationComplete
                || !memory.personalObservationComplete(OBSERVATION_TICKS))
            return "DEFERRED_DISABLED_OR_OBSERVING";
        if (!deferredMetadataValid(now)) return "DEFERRED_INVALID_METADATA";
        for (var entry : new ArrayList<>(data.worldFieldPending.entrySet())) {
            DeferredChunkPlan plan = entry.getValue();
            if (!plan.ownerUuid.equals(player.getUUID().toString())) continue;
            if (plan.expired(now)) {
                data.worldFieldPending.remove(plan.sector);
                dirty = true;
                save(server);
                return dirty ? "DEFERRED_EXPIRATION_SAVE_FAILED" : "DEFERRED_EXPIRED";
            }
            try {
                var candidate = DeferredWorldFieldService.whenReady(
                        server, player, data, plan, now);
                if (candidate == null) {
                    if (Boolean.getBoolean("operator.reality.ciClientSmoke")
                            && "true".equals(System.getenv("GITHUB_ACTIONS"))) {
                        var debugPos = new BlockPos(plan.targetX,plan.preferredY,plan.targetZ);
                        var debugLevel = (ServerLevel) player.level();
                        OperatorMod.LOGGER.info(
                                "OPERATOR_DEFERRED_DIAG now={} cooldownRemaining={} "
                                + "targetLoaded={} sourceLoaded={} playerZ={} planSector={} "
                                + "fieldHistory={} fieldPending={}",
                                now, Math.max(0L, WorldFieldPolicy.COOLDOWN_TICKS
                                        - (now - data.lastWorldFieldTick)),
                                debugLevel.hasChunkAt(debugPos),
                                debugLevel.hasChunkAt(debugPos.east()),
                                player.getZ(), plan.sector,
                                data.worldFieldHistory.size(), data.worldFieldPending.size());
                    }
                    return "DEFERRED_NOT_READY";
                }
                TransactionJournalStorage.save(server, candidate.journal());
                // Each change has a fully materialized BEFORE image in its own
                // durable journal. A crash after this save but before memory
                // flush is caught by the existing orphan-journal audit.
                // The source may be in another 64x64 sector. Reserve all
                // touched sectors under the SAME durable V2 journal before
                // applying any block write. The on-disk audit reconstructs
                // these reservations independently after a restart.
                var footprint = ru.ehtid.operator.transaction.WorldFieldSectorFootprint
                        .sectors(candidate.journal());
                if (footprint.isEmpty())
                    return "DEFERRED_INVALID_FOOTPRINT";
                String reference = player.getUUID() + "/" + candidate.journal().journalId;
                for (String sector : footprint)
                    data.worldFieldHistory.put(sector, reference);
                data.worldFieldPending.remove(plan.sector);
                data.lastWorldFieldTick = now;
                dirty = true;
                save(server);
                if (dirty) {
                    data.worldFieldEnabled = false;
                    return "DEFERRED_MEMORY_PERSIST_FAILED";
                }
                return TunnelFoldService.apply(server, player, candidate.journal());
            } catch (IOException | RuntimeException error) {
                OperatorMod.LOGGER.warn("Deferred cross-chunk attempt refused: {}",
                        error.toString());
                return "DEFERRED_REFUSED";
            }
        }
        return "DEFERRED_NO_OWNER_PLAN";
    }

    /**
     * A readiness hint only. Fabric CHUNK_LOAD does not guarantee the chunk
     * has not already been sent to clients, so NEVER mutate here.
     */
    public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (!loaded || data.worldFieldPending == null || data.worldFieldPending.isEmpty())
            return;
        String dimension = level.dimension().toString();
        for (DeferredChunkPlan plan : data.worldFieldPending.values()) {
            if (plan == null || !plan.valid() || !dimension.equals(plan.dimension))
                continue;
            if (plan.chunkZ() == chunkZ
                    && (plan.targetChunkX() == chunkX || plan.sourceChunkX() == chunkX)) {
                OperatorMod.LOGGER.debug(
                        "Deferred sector {} saw chunk ({},{}); recheck during later server tick",
                        plan.sector, chunkX, chunkZ);
                break;
            }
        }
    }

    private String attemptWorldField(MinecraftServer server, ServerPlayer player,
                                     PlayerMemory memory, long now) {
        if (!data.worldFieldEnabled || !data.autonomousAnomaliesEnabled)
            return "WORLD_FIELD_DISABLED";
        if (!data.observationComplete
                || !memory.personalObservationComplete(OBSERVATION_TICKS))
            return "WORLD_FIELD_OBSERVING";
        var candidate = WorldFieldService.prepare(server, player, data, now);
        if (candidate == null) return "WORLD_FIELD_NO_SAFE_SECTOR";
        try {
            // PREPARED before world-index persistence; a crash in this gap is
            // detected by historyMatchesDisk, never silently duplicated.
            TransactionJournalStorage.save(server, candidate.journal());
            data.worldFieldHistory.put(candidate.sector(),
                    player.getUUID() + "/" + candidate.journal().journalId);
            data.lastWorldFieldTick = now;
            dirty = true;
            save(server);
            if (dirty) return "WORLD_FIELD_MEMORY_PERSIST_FAILED";
            // Actual production WAL apply; this rechecks every player's
            // proximity immediately before touching live chunks.
            return TunnelFoldService.apply(server, player, candidate.journal());
        } catch (IOException | RuntimeException error) {
            OperatorMod.LOGGER.warn("World field event refused: {}", error.toString());
            return "WORLD_FIELD_REFUSED";
        }
    }

    public String setWorldField(MinecraftServer server, boolean enabled) {
        if (!loaded) return "Operator memory not loaded";
        boolean previous = data.worldFieldEnabled;
        data.worldFieldEnabled = enabled;
        dirty = true;
        save(server);
        if (dirty) {
            data.worldFieldEnabled = previous;
            return "World field setting FAILED to persist";
        }
        return "World field " + (enabled ? "ENABLED" : "DISABLED")
                + " | requires separate /operator anomaly enable | max "
                + WorldFieldPolicy.MAX_SECTORS + " distant sectors";
    }

    public String getWorldFieldStatus(MinecraftServer server) {
        if (!loaded) return "Operator memory not loaded";
        String safety;
        try {
            safety = WorldFieldService.historyMatchesDisk(server, data.worldFieldHistory)
                    ? "history verified" : "HISTORY MISMATCH: WRITES BLOCKED";
        } catch (IOException | RuntimeException error) {
            safety = "HISTORY AUDIT FAILED: WRITES BLOCKED";
        }
        return "World field " + (data.worldFieldEnabled ? "ENABLED" : "DISABLED")
                + " | anomaly master " + (data.autonomousAnomaliesEnabled ? "ENABLED" : "DISABLED")
                + " | sectors " + data.worldFieldHistory.size()
                + "/" + WorldFieldPolicy.MAX_SECTORS
                + " | deferred " + data.worldFieldPending.size()
                + "/" + DeferredChunkPlan.MAX_PENDING + " | " + safety;
    }

    public String rollbackWorldField(ServerPlayer player, MinecraftServer server) {
        if (!loaded || data.worldFieldHistory == null) return "World field not loaded";
        String prefix = player.getUUID().toString() + "/";
        List<String> values = new ArrayList<>(data.worldFieldHistory.values());
        for (int i = values.size() - 1; i >= 0; i--) {
            String ref = values.get(i);
            if (ref == null || !ref.startsWith(prefix)) continue;
            // The full underlying engine verifies WAL, world-state preimages,
            // ownership, and for global fields the same absence of viewers.
            return TunnelFoldService.rollback(server, player, ref.substring(prefix.length()));
        }
        return "No recorded world-field transaction owned by this player";
    }

    /**
     * Test-only accelerated observation; requires an isolated world marker,
     * explicit JVM flag, GitHub Actions and no connected players. A synthetic
     * ServerPlayer supplies position/gaze, while every changed BlockState and
     * all WAL files belong to the actual dedicated-server world.
     */
    public String ciAttemptAutonomousFold(MinecraftServer server, ServerPlayer actor,
                                          boolean worldReady, boolean personalReady, long now) {
        if (!Boolean.getBoolean("operator.reality.ciTunnelFold")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || !server.getPlayerList().getPlayers().isEmpty()
                || !java.nio.file.Files.isRegularFile(server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-tunnel-fold.allow")))
            return "OPERATOR_TUNNEL_CI_DENIED";
        if (!loaded) return "CI_OBSERVER_NOT_READY";

        PlayerMemory memory = data.players.computeIfAbsent(
                actor.getUUID().toString(), ignored -> new PlayerMemory());
        memory.uuid = actor.getUUID().toString();
        memory.name = "EHTID_CI";
        memory.observedTicks = personalReady ? OBSERVATION_TICKS : OBSERVATION_TICKS - 1;
        memory.rockBlocksBroken = 160;
        memory.miningSessions = 24;
        if (memory.tunnelCells.isEmpty()) {
            String dim = actor.level().dimension().toString();
            for (int i = 0; i < 63; i++) {
                int x = 1000 + i;
                memory.tunnelCells.put(dim + "|" + x + ",200,0",
                        new PlayerMemory.TunnelCell(x, 200, 0, dim, "minecraft:stone",
                                now - i, PlayerMemory.TUNNEL_SOURCE_MINED));
            }
            memory.tunnelCells.put(dim + "|0,200,0",
                    new PlayerMemory.TunnelCell(0, 200, 0, dim, "minecraft:stone",
                            now, PlayerMemory.TUNNEL_SOURCE_MINED));
        }
        data.observationComplete = worldReady;
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, worldReady, now);
        OperatorBrain.apply(memory, decision, now);
        return attemptAutonomousFold(server, actor, memory, now);
    }

    /**
     * CI-only acceleration for an ACTUAL client-connected ServerPlayer.
     * The normal onServerTick/OperatorBrain/TunnelFoldService later performs
     * the write; this method only seeds observation and enables the opt-in.
     */
    public String ciPrimeRealClientPlayer(MinecraftServer server, ServerPlayer player,
                                          int x, int y, int z) {
        if (!loaded || !Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()
                || !server.getPlayerList().getPlayers().contains(player)
                || server.getPlayerList().getPlayers().size() != 1
                || !java.nio.file.Files.isRegularFile(server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-client.allow")))
            return "DENIED";
        long now = server.overworld().getGameTime();
        PlayerMemory memory = getOrCreate(player, now);
        memory.observedTicks = Math.max(memory.observedTicks, OBSERVATION_TICKS);
        memory.rockBlocksBroken = Math.max(memory.rockBlocksBroken, 160L);
        memory.miningSessions = Math.max(memory.miningSessions, 24L);
        memory.tunnelCells.clear();
        String dim = player.level().dimension().toString();
        for (int i = 0; i < 63; i++) {
            int cellX = 1000 + i;
            memory.tunnelCells.put(dim + "|" + cellX + "," + y + ",0",
                    new PlayerMemory.TunnelCell(cellX, y, 0, dim, "minecraft:stone",
                            now - i, PlayerMemory.TUNNEL_SOURCE_MINED));
        }
        memory.tunnelCells.put(dim + "|" + x + "," + y + "," + z,
                new PlayerMemory.TunnelCell(x, y, z, dim, "minecraft:stone",
                        now, PlayerMemory.TUNNEL_SOURCE_MINED));
        data.observationComplete = true;
        data.autonomousAnomaliesEnabled = true;
        OperatorBrain.Decision decision = OperatorBrain.evaluate(memory, true, now);
        OperatorBrain.apply(memory, decision, now);
        if (!"READY".equals(decision.phase())
                || !"TUNNEL_FOLD".equals(decision.event()))
            return "NO_PLAN_" + decision.phase() + "_" + decision.event();
        dirty = true;
        save(server);
        return dirty ? "SAVE_FAILED" : "CI_PRIMED";
    }

    public String ciPrimeWorldField(MinecraftServer server, ServerPlayer player) {
        if (!loaded || !Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()
                || server.getPlayerList().getPlayers().size() != 1
                || !server.getPlayerList().getPlayers().contains(player)
                || !java.nio.file.Files.isRegularFile(server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-world-field-start.allow")))
            return "DENIED";
        data.worldFieldEnabled = true;
        data.autonomousAnomaliesEnabled = true;
        data.observationComplete = true;
        data.lastWorldFieldTick = 0L;
        dirty = true;
        save(server);
        return dirty ? "FIELD_SAVE_FAILED" : "CI_FIELD_PRIMED";
    }

    /**
     * CI-only proof that a future two-chunk plan can be persisted while BOTH
     * target chunks are still absent. Test then explicitly loads them, and
     * normal server ticks (not this method) perform the full field WAL.
     */
    public String ciStageDeferredBoundary(MinecraftServer server, ServerPlayer player,
                                          int x, int y, int z) {
        if (!loaded || !Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS"))
                || server.isDedicatedServer()
                || server.getPlayerList().getPlayers().size() != 1
                || !server.getPlayerList().getPlayers().contains(player)
                || !java.nio.file.Files.isRegularFile(server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("operator_reality").resolve("ci-deferred-start.allow")))
            return "DENIED";
        if (Math.floorMod(x,16) != 15) return "NOT_A_CHUNK_EDGE";
        ServerLevel level = (ServerLevel) player.level();
        if (level.hasChunkAt(new BlockPos(x,y,z))
                || level.hasChunkAt(new BlockPos(x+1,y,z)))
            return "BORDER_ALREADY_LOADED";
        long now = server.overworld().getGameTime();
        PlayerMemory memory = getOrCreate(player, now);
        memory.observedTicks = Math.max(memory.observedTicks, OBSERVATION_TICKS);
        data.observationComplete = true;
        data.autonomousAnomaliesEnabled = true;
        data.worldFieldEnabled = true;
        // CI-only accelerated cooldown while maintaining production validation
        // and independent real renderer/WAL assertions.
        data.lastWorldFieldTick = Math.max(0L,
                now - WorldFieldPolicy.COOLDOWN_TICKS);
        data.worldFieldPending.clear();
        DeferredChunkPlan plan = DeferredChunkPlan.create(player.getUUID(),
                level.dimension().toString(), x,y,z,now);
        if (data.worldFieldHistory.containsKey(plan.sector))
            return "SECTOR_ALREADY_USED";
        data.worldFieldPending.put(plan.sector, plan);
        data.lastDeferredPlanTick = now;
        dirty = true;
        save(server);
        return dirty ? "SAVE_FAILED" : "CI_DEFERRED_STAGED";
    }

    public String ciFoldJournalId(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        return memory == null || memory.autonomousFoldJournalId == null
                ? "" : memory.autonomousFoldJournalId;
    }

    public String setAutonomousAnomalies(MinecraftServer server, boolean enabled) {
        if (!loaded) return "Operator memory is not loaded";
        boolean previous = data.autonomousAnomaliesEnabled;
        data.autonomousAnomaliesEnabled = enabled;
        dirty = true;
        save(server);
        if (dirty) {
            data.autonomousAnomaliesEnabled = previous;
            return "Anomaly setting could not be saved; state unchanged";
        }
        return enabled ? "Autonomous tunnel folds ENABLED for this world (experimental, one per player)"
                : "Autonomous tunnel folds DISABLED for this world";
    }

    public String getAutonomousAnomalyStatus(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        String id = memory == null ? "" : memory.autonomousFoldJournalId;
        return "Tunnel folds " + (data.autonomousAnomaliesEnabled ? "ENABLED" : "DISABLED")
                + " | player journal " + (id.isEmpty() ? "none" : id)
                + " | " + (id.isEmpty() ? "awaiting TUNNEL_FOLD and safe cave geometry"
                        : TunnelFoldService.status(server, player, id));
    }

    public String rollbackAutonomousAnomaly(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null || memory.autonomousFoldJournalId.isEmpty())
            return "No autonomous tunnel fold recorded for this player";
        return TunnelFoldService.rollback(server, player, memory.autonomousFoldJournalId);
    }

    /** Opt-in server-local preimage capture. No block is placed, removed or moved. */
    public String captureConstructionSnapshot(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        return StructureSnapshotService.capture(server, player, memory).message();
    }

    /** Check a saved snapshot against the present world; never executes a relocation. */
    public String previewConstructionRelocation(ServerPlayer player, MinecraftServer server,
                                                int x, int y, int z) {
        if (!loaded) return "Operator memory is not loaded";
        return StructureSnapshotService.preflight(server, player, x, y, z).message();
    }

    /** Explicit read-only prepare with durable source and target before-images. */
    public String prepareConstructionTransaction(ServerPlayer player, MinecraftServer server,
                                                 int x, int y, int z) {
        if (!loaded) return "Operator memory is not loaded";
        return TransactionPreparationService.prepare(server, player, x, y, z).message();
    }

    /** Reads and verifies the most recent prepared journal; no world change. */
    public String getConstructionTransactionStatus(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        return TransactionPreparationService.status(server, player).message();
    }

    /** Check the latest PREPARED journal against the actual loaded world without writes. */
    public String auditConstructionRecovery(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        return RecoveryAuditService.audit(server, player);
    }

    /** Shows the latest durable recovery audit checkpoint after server restarts. */
    public String getRecoveryCheckpointStatus(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        return RecoveryAuditService.checkpointStatus(server, player);
    }

    /** Explicit privileged sandbox only; no autonomous world mutation. */
    public String executeSandboxRelocation(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        String result = ExperimentalRelocationService.apply(server, player);
        if (result.startsWith("Sandbox relocation COMMITTED:")) {
            return synchronizeConstructionAfterSandbox(player, server, false, result);
        }
        return result;
    }

    /** Manually restore exact simple-block preimages for this mod's executed operation. */
    public String rollbackSandboxRelocation(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        String result = ExperimentalRelocationService.rollback(server, player);
        if (result.startsWith("Sandbox rollback ROLLED_BACK")
                || result.startsWith("Sandbox transaction already ROLLED_BACK")) {
            return synchronizeConstructionAfterSandbox(player, server, true, result);
        }
        return result;
    }

    private String synchronizeConstructionAfterSandbox(ServerPlayer player,
                                                       MinecraftServer server, boolean reverse,
                                                       String result) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) return result + " | player construction memory is unavailable";
        try {
            var journal = TransactionJournalStorage.loadLatest(
                    server, player.getUUID().toString());
            var from = reverse ? journal.target : journal.source;
            int shifted = memory.shiftTrackedConstruction(
                    journal.dimension, from.minX, from.minY, from.minZ,
                    from.maxX, from.maxY, from.maxZ,
                    reverse ? -journal.offsetX : journal.offsetX,
                    reverse ? -journal.offsetY : journal.offsetY,
                    reverse ? -journal.offsetZ : journal.offsetZ,
                    server.overworld().getGameTime());
            if (shifted < 0) return result
                    + " | WARNING: remembered destination has a conflict; construction map unchanged";
            if (shifted > 0) {
                dirty = true;
                save(server);
            }
            return result + " | construction memory remapped: " + shifted
                    + " | placement counters unchanged";
        } catch (IOException | RuntimeException problem) {
            return result + " | WARNING: construction memory could not synchronize: "
                    + problem.getMessage();
        }
    }

    /** Read only the durable write-ahead authorization and progress. */
    public String getSandboxRelocationStatus(ServerPlayer player, MinecraftServer server) {
        if (!loaded) return "Operator memory is not loaded";
        return ExperimentalRelocationService.status(server, player);
    }

    public String getBrainProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "Operator Brain: no player profile yet. The planner remains passive until observation data is collected.";
        }
        return "Operator Brain"
                + " | phase: " + memory.operatorPhase
                + " | behaviour: " + memory.operatorArchetype
                + " | planned intervention: " + memory.plannedOperatorEvent
                + " | confidence: " + Math.round(memory.operatorConfidence * 100.0D) + "%"
                + " | decisions recorded: " + memory.operatorDecisionCount
                + " | last evaluation tick: " + memory.lastBrainEvaluationAt
                + " | last decision tick: " + memory.lastOperatorDecisionAt
                + " | reason: " + memory.operatorReason
                + " | execution: disabled";
    }

    public String getPlayerProfile(ServerPlayer player, MinecraftServer server) {
        long now = server.overworld().getGameTime();
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "No profile yet. The Observer creates it on the next server tick.";
        }

        long personalRemaining = Math.max(0L, OBSERVATION_TICKS - memory.observedTicks);
        return "Profile: " + memory.name
                + " | active observation: " + Math.min(memory.observedTicks, OBSERVATION_TICKS) + "/" + OBSERVATION_TICKS + " ticks"
                + " | personal remaining: " + personalRemaining
                + " | blocks broken: " + memory.blocksBroken
                + " | rock/ore blocks: " + memory.rockBlocksBroken
                + " | mapped tunnel cells: " + memory.getTunnelCellCount()
                + " | mined tunnel cells: " + memory.getMinedTunnelCellCount()
                + " | nearby discovered air cells: " + memory.getObservedAirCellCount()
                + " | mining sessions: " + memory.miningSessions
                + " | longest continuous mining chain: " + memory.longestMiningChain
                + " | route samples: " + memory.route.size()
                + " | movement samples: " + memory.movementSamples
                + " | distance: " + Math.round(memory.distanceTravelled) + " blocks"
                + " | dominant mining direction: " + memory.dominantMiningDirection()
                + " | most mined block: " + memory.mostMinedBlockType()
                + " | most visited dimension: " + memory.mostVisitedDimension()
                + " | mining data sufficient: " + (memory.hasEnoughMiningData() ? "yes" : "not yet")
                + " | player-placed blocks: " + memory.getConstructionBlockCount()
                + " | connected structures: " + memory.recognizedStructureCount
                + " | building candidates: " + memory.recognizedBuildingCount
                + " | largest structure: " + memory.largestStructureBlockCount + " blocks"
                + " | largest building candidate: " + memory.largestBuildingBlockCount + " blocks"
                + " | enclosed void estimate: " + memory.largestEnclosedVoidCells + " cells"
                + " | enclosed rooms: " + memory.recognizedRoomCount
                + " | door links: " + memory.recognizedRoomConnections
                + " | furnished rooms: " + memory.recognizedFurnishedRoomCount
                + " | shell coverage floor/roof/walls: "
                + Math.round(memory.largestFloorCoverage * 100.0D) + "/"
                + Math.round(memory.largestRoofCoverage * 100.0D) + "/"
                + Math.round(memory.largestWallCoverage * 100.0D) + "%"
                + " | operator readiness: " + (isPlayerReadyForOperator(player) ? "ready" : "still observing")
                + " | world tick: " + now;
    }

    public String getConstructionProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "Construction Intelligence: no player profile yet. Place blocks with a block item to begin recording your builds.";
        }

        String dimension = player.level().dimension().toString();
        ConstructionAnalyzer.Analysis analysis =
                ConstructionAnalyzer.analyze(memory.constructionBlocks.values(), dimension);
        ConstructionAnalyzer.Structure bestBuilding = analysis.structures().stream()
                .filter(ConstructionAnalyzer.Structure::buildingCandidate)
                .max(java.util.Comparator
                        .comparingInt(ConstructionAnalyzer.Structure::enclosedVoidCells)
                        .thenComparingInt(ConstructionAnalyzer.Structure::blockCount))
                .orElse(null);
        String details = analysis.structures().stream().limit(3)
                .map(structure -> structure.kind()
                        + " " + structure.blockCount() + " blocks"
                        + " | " + structure.bounds()
                        + " | floor/roof/walls "
                        + Math.round(structure.floorCoverage() * 100.0D) + "/"
                        + Math.round(structure.roofCoverage() * 100.0D) + "/"
                        + Math.round(structure.wallCoverage() * 100.0D) + "%"
                        + " | unoccupied/enclosed cells: "
                        + (structure.interiorScanComplete()
                                ? structure.unoccupiedCells() + "/" + structure.enclosedVoidCells()
                                        + " cells" : "scan capped")
                        + " | doors/windows/stairs/slabs "
                        + structure.doorCount() + "/" + structure.windowBlockCount() + "/"
                        + structure.stairBlockCount() + "/" + structure.slabBlockCount()
                        + " | floors: " + structure.floorLayerCount()
                        + " | rooms/links: " + structure.roomCount() + "/" + structure.doorLinkCount()
                        + " | block states: " + structure.uniqueBlockStates()
                        + " | ticks " + structure.firstPlacedAt() + "-" + structure.lastPlacedAt()
                        + " | " + Math.round(structure.confidence() * 100.0D) + "%")
                .collect(java.util.stream.Collectors.joining(" ; "));

        return "Construction Intelligence"
                + " | dimension: " + dimension
                + " | tracked placed blocks: " + analysis.trackedBlockCount()
                + " | connected structures: " + analysis.structureCount()
                + " | building candidates: " + analysis.buildingCandidateCount()
                + " | enclosed building candidates: " + analysis.structures().stream()
                        .filter(ConstructionAnalyzer.Structure::enclosedBuildingCandidate).count()
                + " | largest structure: " + analysis.largestStructureBlockCount()
                + " blocks"
                + " | largest building candidate: " + analysis.largestBuildingBlockCount()
                + " blocks"
                + " | lifetime placements: " + memory.blocksPlaced
                + " | tracked removals: " + memory.constructionBlocksRemoved
                + " | best enclosed void: "
                + (bestBuilding == null ? 0 : bestBuilding.enclosedVoidCells()) + " cells"
                + " | doors/windows/stairs/slabs: "
                + (bestBuilding == null ? "0/0/0/0"
                        : bestBuilding.doorCount() + "/" + bestBuilding.windowBlockCount() + "/"
                                + bestBuilding.stairBlockCount() + "/" + bestBuilding.slabBlockCount())
                + " | floor levels: " + (bestBuilding == null ? 0 : bestBuilding.floorLayerCount())
                + " | best floor/roof/wall coverage: "
                + Math.round((bestBuilding == null ? 0.0D : bestBuilding.floorCoverage()) * 100.0D) + "/"
                + Math.round((bestBuilding == null ? 0.0D : bestBuilding.roofCoverage()) * 100.0D) + "/"
                + Math.round((bestBuilding == null ? 0.0D : bestBuilding.wallCoverage()) * 100.0D) + "%"
                + (details.isBlank() ? " | no connected structure has reached "
                        + ConstructionAnalyzer.MIN_STRUCTURE_BLOCKS + " blocks yet"
                        : " | largest components: " + details)
                + " | note: enclosed voids are inferred only from recorded placements; this is not a complete world scan and does not capture block-entity NBT.";
    }

    /** Read-only, dimension-scoped room graph diagnostics. */
    public String getRoomProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) return "Room Intelligence: no player profile yet.";
        String dimension = player.level().dimension().toString();
        ConstructionAnalyzer.Analysis analysis =
                ConstructionAnalyzer.analyze(memory.constructionBlocks.values(), dimension);
        StringBuilder summary = new StringBuilder("Room Intelligence | dimension: ")
                .append(dimension);
        int roomCount = 0;
        int linkCount = 0;
        int furnished = 0;
        int shownRooms = 0;
        int shownLinks = 0;
        int capped = 0;
        for (ConstructionAnalyzer.Structure structure : analysis.structures()) {
            if (!structure.interiorScanComplete()) {
                capped++;
                continue;
            }
            roomCount += structure.roomCount();
            linkCount += structure.doorLinkCount();
            furnished += structure.furnishedRoomCount();
            for (var room : structure.rooms()) {
                if (shownRooms++ >= 5) break;
                summary.append(" ; room ").append(room.id())
                        .append(" @ ").append(room.bounds())
                        .append(" | cells ").append(room.cells())
                        .append(" | role ").append(room.role())
                        .append(" | bed/storage/work/smelt ")
                        .append(room.beds()).append("/").append(room.storage()).append("/")
                        .append(room.workstations()).append("/").append(room.smelters());
            }
            for (var link : structure.doorLinks()) {
                if (shownLinks++ >= 5) break;
                summary.append(" ; door ").append(link.roomA()).append("<->")
                        .append(link.roomB()).append(" @ ")
                        .append(link.x()).append(",").append(link.y()).append(",").append(link.z());
            }
        }
        return "Rooms: " + roomCount
                + " | door links: " + linkCount
                + " | furnished: " + furnished
                + " | incomplete scans: " + capped
                + " | " + summary
                + " | geometric estimates from recorded placed blocks only";
    }

    public String getTunnelProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "No player profile yet. Explore an enclosed underground space or mine natural terrain to begin mapping.";
        }
        String dimension = player.level().dimension().toString();
        TunnelAnalyzer.Analysis analysis = TunnelAnalyzer.analyze(memory.tunnelCells.values(), dimension);
        return analysis.describe()
                + " | retained cells across all dimensions: " + memory.getTunnelCellCount()
                + " | mined cells: " + memory.getMinedTunnelCellCount()
                + " | locally discovered air: " + memory.getObservedAirCellCount()
                + " | total terrain blocks mined: " + memory.tunnelBlocksCleared
                + " | note: map combines mined voxels with bounded nearby scans of connected underground air.";
    }

    public String getTunnelNetworkProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "No player profile yet. Explore an enclosed underground space or mine natural terrain to build a tunnel network.";
        }
        String dimension = player.level().dimension().toString();
        Tunnel3DNetworkAnalyzer.Analysis analysis =
                Tunnel3DNetworkAnalyzer.analyze(memory.tunnelCells.values(), dimension);
        return analysis.describe()
                + " | note: height-aware layer-wise 3D reconstruction from mined and locally discovered air voxels.";
    }

    public String getTunnelNetwork2dProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) return "No player profile yet. Explore an enclosed underground space or mine natural terrain.";
        String dimension = player.level().dimension().toString();
        TunnelNetworkAnalyzer.Analysis analysis =
                TunnelNetworkAnalyzer.analyze(memory.tunnelCells.values(), dimension);
        return analysis.describe() + " | legacy horizontal X/Z projection.";
    }

    private void updateWorldObservationPhase(long now) {
        if (!data.observationComplete && data.worldObservationElapsed(now) >= OBSERVATION_TICKS) {
            data.observationComplete = true;
            dirty = true;
            OperatorMod.LOGGER.info("Operator completed the initial passive observation period. The decision planner may now evaluate individually trained players; world mutations remain disabled.");
        }
    }

    private PlayerMemory getOrCreate(ServerPlayer player, long now) {
        UUID uuid = player.getUUID();
        String key = uuid.toString();
        PlayerMemory memory = data.players.computeIfAbsent(key, ignored -> {
            PlayerMemory created = new PlayerMemory();
            created.uuid = key;
            created.firstSeenAt = now;
            return created;
        });
        memory.uuid = key;
        memory.name = player.getName().getString();
        if (memory.firstSeenAt < 0L) memory.firstSeenAt = now;
        memory.lastSeenAt = now;
        return memory;
    }

    private void save(MinecraftServer server) {
        if (!loaded) return;
        data.lastSavedAt = server.overworld().getGameTime();
        dirty = !OperatorStorage.save(server, data);
    }

    private static boolean isTunnelMaterial(String blockId) {
        if (isRockLike(blockId)) return true;
        int separator = blockId.indexOf(':');
        String path = separator >= 0 ? blockId.substring(separator + 1) : blockId;
        return path.equals("dirt")
                || path.equals("coarse_dirt")
                || path.equals("rooted_dirt")
                || path.equals("grass_block")
                || path.equals("podzol")
                || path.equals("mycelium")
                || path.equals("mud")
                || path.equals("packed_mud")
                || path.equals("clay")
                || path.equals("sand")
                || path.equals("red_sand")
                || path.equals("gravel")
                || path.equals("soul_sand")
                || path.equals("soul_soil")
                || path.equals("snow_block")
                || path.equals("ice")
                || path.equals("packed_ice")
                || path.equals("blue_ice")
                || path.equals("terracotta")
                || path.endsWith("_terracotta");
    }

    private static boolean isRockLike(String blockId) {
        int separator = blockId.indexOf(':');
        String path = separator >= 0 ? blockId.substring(separator + 1) : blockId;
        boolean stoneFamily = path.equals("stone")
                || path.startsWith("stone_")
                || path.endsWith("_stone")
                || path.contains("_stone_")
                || path.equals("sandstone")
                || path.equals("red_sandstone");
        return stoneFamily
                || path.contains("deepslate")
                || path.contains("tuff")
                || path.contains("granite")
                || path.contains("diorite")
                || path.contains("andesite")
                || path.contains("calcite")
                || path.contains("dripstone")
                || path.contains("basalt")
                || path.contains("blackstone")
                || path.equals("netherrack")
                || path.equals("end_stone")
                || path.equals("obsidian")
                || path.equals("ancient_debris")
                || path.endsWith("_ore")
                || path.startsWith("ore_");
    }
}
