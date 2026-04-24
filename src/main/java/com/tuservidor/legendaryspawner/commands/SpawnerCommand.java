package com.tuservidor.legendaryspawner.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import com.tuservidor.legendaryspawner.spawn.LegendarySpawnManager;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;

public class SpawnerCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        var base = CommandManager.literal("legendaryspawner")
            .requires(src -> src.hasPermissionLevel(2) || isAdmin(src));

        base.then(CommandManager.literal("spawn")
            .executes(ctx -> {
                boolean ok = LegendarySpawnManager.attemptSpawn(true, ctx.getSource(), null);
                return ok ? 1 : 0;
            })
            .then(CommandManager.argument("properties", StringArgumentType.greedyString())
                .executes(ctx -> {
                    String props = StringArgumentType.getString(ctx, "properties");
                    boolean ok = LegendarySpawnManager.attemptSpawn(true, ctx.getSource(), props);
                    return ok ? 1 : 0;
                })
            )
        );

        base.then(CommandManager.literal("remove")
            .executes(ctx -> {
                int removed = LegendarySpawnManager.removeAll(ctx.getSource());
                if (removed == 0) {
                    ctx.getSource().sendMessage(Text.literal(colorize(LegendarySpawner.config.format(LegendarySpawner.config.getMsgNoneActive()))));
                }
                return 1;
            })
        );

        base.then(CommandManager.literal("status")
            .executes(ctx -> {
                int active = LegendarySpawnManager.getActiveCount();
                ctx.getSource().sendMessage(Text.literal(colorize(
                    LegendarySpawner.config.getPrefix() + "&7Legendarios activos: &e" + active
                    + " &8| Intervalo: &e" + LegendarySpawner.config.getSpawnIntervalMinutes() + " min")));
                return 1;
            })
        );

        // --- NUEVO COMANDO: AUDITORÍA ---
        base.then(CommandManager.literal("history")
            .executes(ctx -> {
                ctx.getSource().sendMessage(Text.literal(colorize("&8[&6⚡&8] &eÚltimos 15 Eventos de Legendarios:")));
                List<String> history = LegendarySpawnManager.getAuditHistory();
                for (String line : history) {
                    ctx.getSource().sendMessage(Text.literal(colorize("&7" + line)));
                }
                return 1;
            })
        );
        // --------------------------------

        base.then(CommandManager.literal("reload")
            .executes(ctx -> {
                LegendarySpawner.config.init();
                LegendarySpawnManager.reschedule();
                ctx.getSource().sendMessage(Text.literal(colorize(LegendarySpawner.config.format(LegendarySpawner.config.getMsgReload()))));
                return 1;
            })
        );

        LiteralCommandNode<ServerCommandSource> baseNode = dispatcher.register(base);
        dispatcher.register(CommandManager.literal("ls").requires(baseNode.getRequirement()).redirect(baseNode));
    }

    private static boolean isAdmin(ServerCommandSource src) {
        if (!src.isExecutedByPlayer()) return true;
        try {
            var player = src.getPlayer();
            if (player == null) return false;
            var lp = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(player.getUuid());
            return lp != null && lp.getCachedData().getPermissionData().checkPermission("legendaryspawner.admin").asBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String colorize(String s) {
        return s.replace("&0","§0").replace("&1","§1").replace("&2","§2")
                .replace("&3","§3").replace("&4","§4").replace("&5","§5")
                .replace("&6","§6").replace("&7","§7").replace("&8","§8")
                .replace("&9","§9").replace("&a","§a").replace("&b","§b")
                .replace("&c","§c").replace("&d","§d").replace("&e","§e")
                .replace("&f","§f").replace("&l","§l").replace("&r","§r");
    }
}