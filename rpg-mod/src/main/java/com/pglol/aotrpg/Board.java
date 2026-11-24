package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The board you see when you join: this week's operation, the ranked season, any event that's on,
 * what's new on the server and what's coming up. Admins write the news and the dates:
 * /aotrpg board news &lt;text&gt;, /aotrpg board event &lt;when&gt; &lt;text&gt;, /aotrpg board remove news|event &lt;n&gt;.
 * Anyone can open it again with /board. No shop on it, nothing to buy.
 */
public final class Board {
    private Board() {}

    static final class Entry {
        String date;
        String text;

        Entry(String date, String text) {
            this.date = date;
            this.text = text;
        }
    }

    static final class Data {
        List<Entry> news = new ArrayList<>();
        List<Entry> events = new ArrayList<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            file = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("board.json");
            try {
                if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read board", e);
            }
            if (data == null) data = new Data();
            if (data.news == null) data.news = new ArrayList<>();
            if (data.events == null) data.events = new ArrayList<>();
            if (data.news.isEmpty()) {
                data.news.add(new Entry(today(), "Swing sites: ruined quarters, stone spires, giant groves and wall breaches now stand in every region."));
                data.news.add(new Entry(today(), "Cutscenes fly between shots now, and conversations got a new look."));
                save();
            }
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> AotRpg.SCHEDULER.later(80, () -> {
            if (!h.player.isDisconnected()) send(h.player, false);
        }));
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(CommandManager.literal("board").executes(c -> {
                send(c.getSource().getPlayerOrThrow(), true);
                return 1;
            }));
            d.register(CommandManager.literal("aotrpg").requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("board")
                    .then(CommandManager.literal("news").then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
                        data.news.add(0, new Entry(today(), StringArgumentType.getString(c, "text")));
                        while (data.news.size() > 12) data.news.remove(data.news.size() - 1);
                        save();
                        c.getSource().sendFeedback(() -> Text.literal("Posted to the board.").formatted(Formatting.GREEN), true);
                        return 1;
                    })))
                    .then(CommandManager.literal("event").then(CommandManager.argument("when", StringArgumentType.string())
                        .then(CommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
                            data.events.add(new Entry(StringArgumentType.getString(c, "when"), StringArgumentType.getString(c, "text")));
                            while (data.events.size() > 8) data.events.remove(0);
                            save();
                            c.getSource().sendFeedback(() -> Text.literal("Event added to the board.").formatted(Formatting.GREEN), true);
                            return 1;
                        }))))
                    .then(CommandManager.literal("remove")
                        .then(CommandManager.literal("news").then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> remove(c.getSource(), data.news, IntegerArgumentType.getInteger(c, "n")))))
                        .then(CommandManager.literal("event").then(CommandManager.argument("n", IntegerArgumentType.integer(1)).executes(c -> remove(c.getSource(), data.events, IntegerArgumentType.getInteger(c, "n"))))))
                    .then(CommandManager.literal("list").executes(c -> {
                        int i = 1;
                        for (Entry e : data.news) {
                            int k = i++;
                            c.getSource().sendFeedback(() -> Text.literal("news " + k + ": [" + e.date + "] " + e.text), false);
                        }
                        i = 1;
                        for (Entry e : data.events) {
                            int k = i++;
                            c.getSource().sendFeedback(() -> Text.literal("event " + k + ": [" + e.date + "] " + e.text), false);
                        }
                        return 1;
                    }))));
        });
    }

    private static int remove(net.minecraft.server.command.ServerCommandSource src, List<Entry> list, int n) {
        if (n > list.size()) {
            src.sendFeedback(() -> Text.literal("No entry " + n + ".").formatted(Formatting.RED), false);
            return 0;
        }
        list.remove(n - 1);
        save();
        src.sendFeedback(() -> Text.literal("Removed.").formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private static String clean(String s) {
        return s.replace('|', '/');
    }

    public static void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.Board.ID)) return;
        List<String> rows = new ArrayList<>();
        Weekly.Op op = Weekly.now();
        rows.add("week|" + clean(op.title) + "|" + clean(op.desc) + " · " + Weekly.daysLeft() + " days left|" + op.color);
        rows.add("season|Ranked Season " + RankedSeasons.number() + "|" + RankedSeasons.daysLeft() + " days left · climb the ladder in duels and arenas|" + 0xB06AE0);
        if (AotRpg.EVENTS.running()) {
            long end = AotRpg.EVENTS.endsAt();
            String left = end <= 0 ? "on now" : Math.max(0, (end - System.currentTimeMillis()) / 86_400_000L) + " days left";
            rows.add("live|" + clean(AotRpg.EVENTS.eventName()) + "|" + left + " · earn " + clean(AotRpg.EVENTS.tokenName()) + " from titans and quests|" + 0xE8433A);
        }
        for (Entry e : data.events) rows.add("event|" + clean(e.date) + "|" + clean(e.text) + "|" + 0x9AD0FF);
        for (Entry e : data.news) rows.add("news|" + clean(e.date) + "|" + clean(e.text) + "|" + 0xE8C36F);
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        int online = p.getServer().getPlayerManager().getCurrentPlayerCount();
        // New players (no character made yet) are welcomed; everyone else is welcomed back.
        String hello = (pr == null || !pr.created ? "Welcome, " : "Welcome back, ") + clean(p.getName().getString());
        rows.add("you|" + hello + "|" + (pr == null ? "" : "Level " + pr.level + (pr.prestige > 0 ? " · Prestige " + pr.prestige : "")) + " · " + online + " online|" + 0xEDE3C8);
        ServerPlayNetworking.send(p, new Net.Board(rows, open));
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save board", e);
        }
    }
}
