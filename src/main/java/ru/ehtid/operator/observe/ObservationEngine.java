package ru.ehtid.operator.observe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;

import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.analysis.TunnelAnalyzer;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.storage.OperatorStorage;

public final class ObservationEngine {
    public static final long OBSERVATION_TICKS = 24_000L;
    private static final long MOVEMENT_SAMPLE_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 600L;
    private static final int MAX_PENDING_VOXEL_CHECKS = 256;

    private final Map<String, PendingVoxelCheck> pendingVoxelChecks = new LinkedHashMap<>();
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
            dirty = true;
        }

        if (dirty && now - data.lastSavedAt >= SAVE_INTERVAL) save(server);
    }

    public void onBlockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (player.isCreative() || player.isSpectator() || !loaded) return;

        long now = level.getGameTime();
        PlayerMemory memory = getOrCreate(serverPlayer, now);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        memory.recordBrokenBlock(
                pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().toString(), blockId,
                isRockLike(blockId), isTunnelMaterial(blockId), now
        );
        dirty = true;
    }

    /**
     * Schedules a one-tick-later check for a mined voxel adjacent to a block interaction.
     * If the interaction placed a block into that recorded air cell, the stale voxel is removed.
     */
    public InteractionResult onBlockUse(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator() || !loaded) {
            return InteractionResult.PASS;
        }

        BlockPos target = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
        String dimension = level.dimension().toString();
        boolean trackedByAnyPlayer = data.players.values().stream()
                .anyMatch(memory -> memory.hasTunnelCell(
                        dimension, target.getX(), target.getY(), target.getZ()
                ));
        if (!trackedByAnyPlayer) return InteractionResult.PASS;

        String key = dimension + "|" + target.getX() + "," + target.getY() + "," + target.getZ();
        pendingVoxelChecks.remove(key);
        pendingVoxelChecks.put(key, new PendingVoxelCheck(
                level, target, dimension, level.getGameTime() + 1L
        ));
        while (pendingVoxelChecks.size() > MAX_PENDING_VOXEL_CHECKS) {
            Iterator<String> iterator = pendingVoxelChecks.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        return InteractionResult.PASS;
    }

    private void processPendingVoxelChecks() {
        if (pendingVoxelChecks.isEmpty()) return;

        Iterator<Map.Entry<String, PendingVoxelCheck>> iterator = pendingVoxelChecks.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingVoxelCheck check = iterator.next().getValue();
            if (check.level().getGameTime() < check.dueGameTick()) continue;
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

    private record PendingVoxelCheck(
            Level level,
            BlockPos position,
            String dimension,
            long dueGameTick
    ) { }

    public void onServerStopping(MinecraftServer server) {
        if (loaded) save(server);
        pendingVoxelChecks.clear();
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
        return "Operator 0.3 | " + phase
                + " | world learning: " + Math.min(elapsed, OBSERVATION_TICKS) + "/" + OBSERVATION_TICKS + " ticks"
                + " | remaining: " + remaining + " ticks"
                + " | player profiles: " + data.players.size()
                + " | individually trained: " + personallyTrained
                + " | mapped tunnel cells: " + data.players.values().stream().mapToInt(PlayerMemory::getTunnelCellCount).sum()
                + " | world mutations: disabled in 0.3";
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
                + " | mining sessions: " + memory.miningSessions
                + " | longest continuous mining chain: " + memory.longestMiningChain
                + " | route samples: " + memory.route.size()
                + " | movement samples: " + memory.movementSamples
                + " | distance: " + Math.round(memory.distanceTravelled) + " blocks"
                + " | dominant mining direction: " + memory.dominantMiningDirection()
                + " | most mined block: " + memory.mostMinedBlockType()
                + " | most visited dimension: " + memory.mostVisitedDimension()
                + " | mining data sufficient: " + (memory.hasEnoughMiningData() ? "yes" : "not yet")
                + " | operator readiness: " + (isPlayerReadyForOperator(player) ? "ready" : "still observing")
                + " | world tick: " + now;
    }

    public String getTunnelProfile(ServerPlayer player) {
        PlayerMemory memory = data.players.get(player.getUUID().toString());
        if (memory == null) {
            return "No player profile yet. Stay in the world and mine natural terrain to begin mapping.";
        }
        String dimension = player.level().dimension().toString();
        TunnelAnalyzer.Analysis analysis = TunnelAnalyzer.analyze(memory.tunnelCells.values(), dimension);
        return analysis.describe()
                + " | retained cells across all dimensions: " + memory.getTunnelCellCount()
                + " | total terrain blocks recorded: " + memory.tunnelBlocksCleared
                + " | note: only blocks mined while Operator was active are mapped.";
    }

    private void updateWorldObservationPhase(long now) {
        if (!data.observationComplete && data.worldObservationElapsed(now) >= OBSERVATION_TICKS) {
            data.observationComplete = true;
            dirty = true;
            OperatorMod.LOGGER.info("Operator completed the initial passive observation period. No world mutations are enabled in 0.3.");
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
