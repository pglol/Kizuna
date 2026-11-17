package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Directional swings. Each blade swing is read for its direction (how your view was moving as you
 * swung: a cut from the left, from the right, an overhead chop, a rising cut, or a straight
 * thrust), everyone sees which, and each direction's swing can be slowed or sped up for
 * directional combat. Tuned with /swingspeed (operators), read back with /swingcheck.
 */
public final class Swings {
    private Swings() {}

    public enum Dir { LEFT, RIGHT, OVERHEAD, RISING, THRUST }

    /** Swing time multipliers by direction (1 as made; 1.5 half again as slow). */
    public static final class Config {
        public float[] speed = {1.15f, 1.15f, 1.35f, 1.2f, 0.9f};
        public boolean enabled = true;
    }

    private static Config cfg = new Config();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_swings.json");
    }

    private static void load() {
        try {
            if (Files.exists(file())) {
                Config c = GSON.fromJson(Files.readString(file()), Config.class);
                if (c != null && c.speed != null && c.speed.length == Dir.values().length) cfg = c;
            }
        } catch (Exception ignored) { }
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(cfg));
        } catch (Exception ignored) { }
    }

    private static Net.SwingSpeeds speeds() {
        return new Net.SwingSpeeds(cfg.enabled ? cfg.speed.clone() : new float[] {1, 1, 1, 1, 1});
    }

    private static void push(MinecraftServer server) {
        Net.SwingSpeeds s = speeds();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) if (ServerPlayNetworking.canSend(p, Net.SwingSpeeds.ID)) ServerPlayNetworking.send(p, s);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> load());
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (ServerPlayNetworking.canSend(h.player, Net.SwingSpeeds.ID)) ServerPlayNetworking.send(h.player, speeds());
        });
        // A swing's direction, shown to everyone watching.
        ServerPlayNetworking.registerGlobalReceiver(Net.SwingDir.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            int d = Math.max(0, Math.min(Dir.values().length - 1, payload.dir()));
            Net.SwingSeen seen = new Net.SwingSeen(p.getId(), d);
            for (ServerPlayerEntity o : PlayerLookup.tracking(p)) if (o != p && ServerPlayNetworking.canSend(o, Net.SwingSeen.ID)) ServerPlayNetworking.send(o, seen);
        });
    }

    public static void commands(com.mojang.brigadier.CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("swingcheck").executes(c -> {
            StringBuilder b = new StringBuilder("Swing speeds" + (cfg.enabled ? "" : " (off)") + ":");
            for (Dir dir : Dir.values()) b.append(' ').append(dir.name().toLowerCase(Locale.ROOT)).append(' ').append(String.format(Locale.ROOT, "x%.2f", cfg.speed[dir.ordinal()]));
            b.append(" | animation libraries:");
            boolean any = false;
            for (String id : new String[] {"player-animator", "playeranimator", "geckolib", "azurelib", "bendy-lib", "emotecraft", "playeranimatorapi"}) {
                if (FabricLoader.getInstance().isModLoaded(id)) {
                    b.append(' ').append(id);
                    any = true;
                }
            }
            if (!any) b.append(" none (Danny's swings follow the vanilla arm swing)");
            String s = b.toString();
            c.getSource().sendFeedback(() -> Text.literal(s), false);
            return 1;
        }));
        d.register(CommandManager.literal("swingspeed").requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("on").executes(c -> toggle(c.getSource(), true)))
            .then(CommandManager.literal("off").executes(c -> toggle(c.getSource(), false)))
            .then(CommandManager.argument("direction", StringArgumentType.word())
                .suggests((c, sb) -> {
                    for (Dir dir : Dir.values()) sb.suggest(dir.name().toLowerCase(Locale.ROOT));
                    sb.suggest("all");
                    return sb.buildFuture();
                })
                .then(CommandManager.argument("multiplier", FloatArgumentType.floatArg(0.3f, 3f)).executes(c -> {
                    String name = StringArgumentType.getString(c, "direction").toUpperCase(Locale.ROOT);
                    float m = FloatArgumentType.getFloat(c, "multiplier");
                    if (name.equals("ALL")) java.util.Arrays.fill(cfg.speed, m);
                    else {
                        try {
                            cfg.speed[Dir.valueOf(name).ordinal()] = m;
                        } catch (IllegalArgumentException e) {
                            c.getSource().sendError(Text.literal("Directions: left, right, overhead, rising, thrust, all"));
                            return 0;
                        }
                    }
                    save();
                    push(c.getSource().getServer());
                    c.getSource().sendFeedback(() -> Text.literal("Swing " + name.toLowerCase(Locale.ROOT) + " set to x" + m), true);
                    return 1;
                }))));
    }

    private static int toggle(ServerCommandSource s, boolean on) {
        cfg.enabled = on;
        save();
        push(s.getServer());
        s.sendFeedback(() -> Text.literal("Directional swing speeds " + (on ? "on" : "off")), true);
        return 1;
    }
}
