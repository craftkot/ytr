package ru.ehtid.operator.command;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import ru.ehtid.operator.observe.ObservationEngine;

public final class OperatorCommands {
    private OperatorCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ObservationEngine observer) {
        dispatcher.register(Commands.literal("operator")
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(
                            () -> Component.literal(observer.getStatus(context.getSource().getServer())),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("profile").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getPlayerProfile(player, source.getServer())),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("tunnels").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getTunnelProfile(player)),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("network").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getTunnelNetworkProfile(player)),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("network2d").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getTunnelNetwork2dProfile(player)),
                            false
                    );
                    return 1;
                }))
        );
    }
}
