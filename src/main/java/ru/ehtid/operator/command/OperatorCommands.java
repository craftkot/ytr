package ru.ehtid.operator.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
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
                .then(Commands.literal("brain").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getBrainProfile(player)),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("structures").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getConstructionProfile(player)),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("rooms").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(
                            () -> Component.literal(observer.getRoomProfile(player)),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("snapshot").executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    source.sendSuccess(() -> Component.literal(
                            observer.captureConstructionSnapshot(player, source.getServer())), false);
                    return 1;
                }))
                .then(Commands.literal("preview")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    var player = source.getPlayerOrException();
                                                    int x = IntegerArgumentType.getInteger(context, "x");
                                                    int y = IntegerArgumentType.getInteger(context, "y");
                                                    int z = IntegerArgumentType.getInteger(context, "z");
                                                    source.sendSuccess(() -> Component.literal(
                                                            observer.previewConstructionRelocation(
                                                                    player, source.getServer(), x, y, z)),
                                                            false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("transaction")
                        .then(Commands.literal("status").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.getConstructionTransactionStatus(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("checkpoint").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.getRecoveryCheckpointStatus(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("audit").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.auditConstructionRecovery(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("prepare")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(context -> {
                                                            var source = context.getSource();
                                                            var player = source.getPlayerOrException();
                                                            int x = IntegerArgumentType.getInteger(context, "x");
                                                            int y = IntegerArgumentType.getInteger(context, "y");
                                                            int z = IntegerArgumentType.getInteger(context, "z");
                                                            source.sendSuccess(() -> Component.literal(
                                                                    observer.prepareConstructionTransaction(
                                                                            player, source.getServer(), x, y, z)),
                                                                    false);
                                                            return 1;
                                                        })))))
                )
                .then(Commands.literal("sandbox")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("status").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.getSandboxRelocationStatus(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("apply").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.executeSandboxRelocation(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("rollback").executes(context -> {
                            var source = context.getSource();
                            var player = source.getPlayerOrException();
                            source.sendSuccess(() -> Component.literal(
                                    observer.rollbackSandboxRelocation(player, source.getServer())),
                                    false);
                            return 1;
                        }))
                )
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
