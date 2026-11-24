package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who may play on this server, for betas and playtests. The server is in one of three states:
 * <ul>
 *   <li>open: anyone who can connect plays (the whitelist still applies if it's on);</li>
 *   <li>beta: only testers and admins; everyone else is turned away with the server's message;</li>
 *   <li>closed: admins only (maintenance).</li>
 * </ul>
 * Testers are kept by name and uuid in aot_rpg/access.json. Commands, for admins:
 * /aotrpg access mode open|beta|closed, /aotrpg access add|remove &lt;player&gt;, /aotrpg access list,
 * /aotrpg access message &lt;text&gt;. The launcher hub (servers/hub.json) says which packs are betas;
 * this is what actually keeps the door.
 */
public final class Access {
    private Access() {}

    static final class Data {
        String mode = "open";
        String message = "This server is in a closed playtest right now. Ask an admin for a tester spot.";
        Map<String, String> testers = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean tester(ServerPlayerEntity p) {
        return data.testers.containsKey(p.getUuidAsString());
    }

    private static boolean admin(ServerPlayerEntity p) {
        return p.getServer().getPlayerManager().isOperator(p.getGameProfile());
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            file = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("access.json");
            try {
                if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read access list", e);
            }
            if (data == null) data = new Data();
            if (data.testers == null) data.testers = new LinkedHashMap<>();
            AotRpg.LOG.info("Access: {} ({} testers)", data.mode, data.testers.size());
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayerEntity p = h.player;
            if (admin(p)) return;
            boolean ok = switch (data.mode) {
                case "beta" -> tester(p);
                case "closed" -> false;
                default -> true;
            };
            if (ok) {
                // Keep the name fresh for the list.
                if (tester(p)) data.testers.put(p.getUuidAsString(), p.getName().getString());
                return;
            }
            String head = data.mode.equals("closed") ? "Server closed for maintenance" : "Closed playtest";
            p.networkHandler.disconnect(Text.literal(head).formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal("\n\n" + data.message).formatted(Formatting.WHITE)));
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("aotrpg").requires(src -> src.hasPermissionLevel(3))
            .then(CommandManager.literal("access")
                .then(CommandManager.literal("mode").then(CommandManager.argument("mode", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (String m : new String[] {"open", "beta", "closed"}) b.suggest(m);
                        return b.buildFuture();
                    })
                    .executes(c -> {
                        String m = StringArgumentType.getString(c, "mode").toLowerCase(java.util.Locale.ROOT);
                        if (!m.equals("open") && !m.equals("beta") && !m.equals("closed")) {
                            c.getSource().sendFeedback(() -> Text.literal("Modes: open, beta, closed").formatted(Formatting.RED), false);
                            return 0;
                        }
                        data.mode = m;
                        save();
                        c.getSource().sendFeedback(() -> Text.literal("Server access: " + m).formatted(Formatting.GREEN), true);
                        return 1;
                    })))
                .then(CommandManager.literal("add").then(CommandManager.argument("players", GameProfileArgumentType.gameProfile())
                    .executes(c -> change(c.getSource(), GameProfileArgumentType.getProfileArgument(c, "players"), true))))
                .then(CommandManager.literal("remove").then(CommandManager.argument("players", GameProfileArgumentType.gameProfile())
                    .executes(c -> change(c.getSource(), GameProfileArgumentType.getProfileArgument(c, "players"), false))))
                .then(CommandManager.literal("message").then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
                    data.message = StringArgumentType.getString(c, "text");
                    save();
                    c.getSource().sendFeedback(() -> Text.literal("Turn-away message set."), true);
                    return 1;
                })))
                .then(CommandManager.literal("list").executes(c -> {
                    c.getSource().sendFeedback(() -> Text.literal("Access: " + data.mode + " · " + data.testers.size() + " testers").formatted(Formatting.GOLD), false);
                    for (String n : data.testers.values()) c.getSource().sendFeedback(() -> Text.literal("  " + n), false);
                    return 1;
                })))));
    }

    private static int change(ServerCommandSource src, Collection<GameProfile> profiles, boolean add) {
        for (GameProfile g : profiles) {
            if (add) data.testers.put(g.getId().toString(), g.getName());
            else data.testers.remove(g.getId().toString());
            src.sendFeedback(() -> Text.literal((add ? "Tester added: " : "Tester removed: ") + g.getName()).formatted(add ? Formatting.GREEN : Formatting.YELLOW), true);
            if (!add && !data.mode.equals("open")) {
                ServerPlayerEntity p = src.getServer().getPlayerManager().getPlayer(g.getId());
                if (p != null && !admin(p)) p.networkHandler.disconnect(Text.literal("Your tester spot has ended. Thanks for playing!").formatted(Formatting.GOLD));
            }
        }
        save();
        return profiles.size();
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save access list", e);
        }
    }
}
