package ru.ehtid.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import ru.ehtid.operator.command.OperatorCommands;
import ru.ehtid.operator.observe.ObservationEngine;
import ru.ehtid.operator.storage.RelocationWatchdogStorage;
import ru.ehtid.operator.transaction.ClientTunnelSmoke;
import ru.ehtid.operator.transaction.ClientWorldFieldSmoke;
import ru.ehtid.operator.transaction.ClientDeferredChunkSmoke;
import java.io.IOException;

public final class OperatorMod implements ModInitializer {
    public static final String MOD_ID = "operator_reality";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ObservationEngine OBSERVER = new ObservationEngine();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(OBSERVER::onServerStarted);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var scan = RelocationWatchdogStorage.scan(server);
                if (scan.blocksNewWrites()) {
                    LOGGER.error("Unfinished or damaged sandbox WAL files detected on server start. "
                            + "All new sandbox relocations are blocked until resolved. {}",
                            scan.summary());
                } else if (scan.committed() > 0) {
                    LOGGER.info("Sandbox WAL audit on startup: {}", scan.summary());
                }
            } catch (IOException failure) {
                LOGGER.error("Cannot read execution WAL directory; new sandbox transfers blocked.",
                        failure);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(OBSERVER::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(OBSERVER::onServerTick);
        // Chunk-load events are only signals. All mutations are deferred
        // to the next server tick with full WAL and visibility rechecks.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) ->
                OBSERVER.onChunkLoaded(level, chunk.getPos().x(), chunk.getPos().z()));
        // Actual client-world smoke only: a real ServerPlayer exists, renders
        // through LWJGL under Xvfb and is required for the image assertions.
        if (Boolean.getBoolean("operator.reality.ciClientSmoke")
                && "true".equals(System.getenv("GITHUB_ACTIONS"))) {
            ClientTunnelSmoke clientSmoke = new ClientTunnelSmoke();
            ServerTickEvents.END_SERVER_TICK.register(
                    server -> clientSmoke.tick(server, OBSERVER));
            ClientWorldFieldSmoke worldFieldSmoke = new ClientWorldFieldSmoke();
            ServerTickEvents.END_SERVER_TICK.register(
                    server -> worldFieldSmoke.tick(server, OBSERVER));
            ClientDeferredChunkSmoke deferredSmoke = new ClientDeferredChunkSmoke();
            ServerTickEvents.END_SERVER_TICK.register(
                    server -> deferredSmoke.tick(server, OBSERVER));
        }
        PlayerBlockBreakEvents.AFTER.register(OBSERVER::onBlockBroken);
        UseBlockCallback.EVENT.register(OBSERVER::onBlockUse);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> OperatorCommands.register(dispatcher, OBSERVER)
        );

        LOGGER.info("Operator: Reality 0.25.0 loaded. Opt-in autonomous tunnel folding uses the durable WAL; automatic recovery remains disabled.");
    }
}
