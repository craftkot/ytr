package ru.ehtid.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import ru.ehtid.operator.command.OperatorCommands;
import ru.ehtid.operator.observe.ObservationEngine;

public final class OperatorMod implements ModInitializer {
    public static final String MOD_ID = "operator_reality";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final ObservationEngine OBSERVER = new ObservationEngine();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(OBSERVER::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(OBSERVER::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(OBSERVER::onServerTick);
        PlayerBlockBreakEvents.AFTER.register(OBSERVER::onBlockBroken);
        UseBlockCallback.EVENT.register(OBSERVER::onBlockUse);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, buildContext, selection) -> OperatorCommands.register(dispatcher, OBSERVER)
        );

        LOGGER.info("Operator: Reality 0.10.0 loaded. Persistent construction tracking, bounded room topology, door connectivity, furnishing heuristics and shadow planning are enabled; world mutation remains disabled.");
    }
}
