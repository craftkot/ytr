package ru.ehtid.operator.observe;

import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.data.OperatorWorldData;
import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.storage.OperatorStorage;

public final class ObservationEngine {
    public static final long OBSERVATION_TICKS = 24_000L;
    private static final long MOVEMENT_SAMPLE_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 600L;

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

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            PlayerMemory memory = getOrCreate(player, now);
            memory.recordActiveTick(now);

            if (now % MOVEMENT_SAMPLE_INTERVAL == 0L) {
                memory.recordMovement(
                        player.getX(), player.getY(), player.getZ(),
                        player.level().dimension().location().toString(), now
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
                level.dimension().location().toString(), blockId, isRockLike(blockId), now
        );
        dirty = true;
    }

    public void onServerStopping(MinecraftServer server) {
        if (loaded) save(server);
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
        return "Operator 0.2 | " + phase
                + " | world learning: " + Math.min(elapsed, OBSERVATION_TICKS) + "/" + OBSERVATION_TICKS + " ticks"
                + " | remaining: " + remaining + " ticks"
                + " | player profiles: " + data.players.size()
                + " | individually trained: " + personallyTrained
                + " | world mutations: disabled in 0.2";
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

    private void updateWorldObservationPhase(long now) {
        if (!data.observationComplete && data.worldObservationElapsed(now) >= OBSERVATION_TICKS) {
            data.observationComplete = true;
            dirty = true;
            OperatorMod.LOGGER.info("Operator completed the initial passive observation period. No world mutations are enabled in 0.2.");
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
