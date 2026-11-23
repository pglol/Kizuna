package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranked seasons for the arena: six weeks each. When one ends the top three ratings are paid and
 * written into the Hall of Fame (and earn the Season Champion title), and everyone's rating is
 * pulled halfway back toward 1000 so the next climb is a real one (applied as each player next logs
 * in, so nobody's left out).
 */
public final class RankedSeasons {
    private RankedSeasons() {}

    private static final long LENGTH = 42L * 86_400_000L;
    private static final long[] PAY_MARKS = {15000, 8000, 4000};
    private static final long[] PAY_GOLD = {150, 75, 40};

    static final class Data {
        int number = 1;
        long endsAt;
        List<String> hall = new ArrayList<>();
        /** uuid -> "season|place" waiting to be paid at next login. */
        Map<String, String> owed = new HashMap<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static MinecraftServer server;

    public static int number() {
        return data.number;
    }

    public static int daysLeft() {
        return (int) Math.max(0, (data.endsAt - System.currentTimeMillis()) / 86_400_000L);
    }

    public static List<String> hall() {
        return data.hall;
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            file = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("ranked_season.json");
            try {
                if (Files.exists(file)) data = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read ranked_season.json", e);
            }
            if (data == null) data = new Data();
            if (data.hall == null) data.hall = new ArrayList<>();
            if (data.owed == null) data.owed = new HashMap<>();
            if (data.endsAt == 0) {
                data.endsAt = System.currentTimeMillis() + LENGTH;
                save();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 1200 == 77 && data.endsAt > 0 && System.currentTimeMillis() >= data.endsAt) roll();
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> settle(h.player));
    }

    /** A player's rating belongs to this season (pulled back if it's from an older one), and anything owed is paid. */
    public static void settle(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return;
        if (pr.ratingSeason != data.number) {
            if (pr.ratingSeason != 0) pr.pvpRating = 1000 + (pr.pvpRating - 1000) / 2;
            pr.ratingSeason = data.number;
            AotRpg.PROFILES.save(p.getUuid());
        }
        String owed = data.owed.remove(p.getUuid().toString());
        if (owed != null) {
            String[] a = owed.split("\\|");
            int season = Integer.parseInt(a[0]), place = Integer.parseInt(a[1]);
            AotRpg.WALLET.addMarks(p, PAY_MARKS[place], null);
            AotRpg.PROFILES.account(p.getUuid()).gold += PAY_GOLD[place];
            AotRpg.PROFILES.saveAccount(p.getUuid());
            AotRpg.TASKS.count(p, Tasks.CHAMPION, 1);
            Reveal.show(p, "Season " + season + ": #" + (place + 1), "+" + PAY_MARKS[place] + " Marks · +" + PAY_GOLD[place] + " Gold · Season Champion title",
                "minecraft:netherite_sword", 4);
            save();
        }
    }

    private static void roll() {
        List<Profile> all = AotRpg.PROFILES.all();
        all.removeIf(pr -> !pr.created || pr.ratingSeason != data.number || pr.pvpWins + pr.pvpLosses < 5);
        all.sort((x, y) -> Integer.compare(y.pvpRating, x.pvpRating));
        StringBuilder line = new StringBuilder("Season " + data.number + ": ");
        for (int i = 0; i < Math.min(3, all.size()); i++) {
            Profile pr = all.get(i);
            line.append(i == 0 ? "" : ", ").append(pr.name).append(" (").append(pr.pvpRating).append(")");
            if (pr.uuid != null) data.owed.put(pr.uuid, data.number + "|" + i);
        }
        if (!all.isEmpty()) data.hall.add(0, line.toString());
        int ended = data.number;
        data.number++;
        data.endsAt = System.currentTimeMillis() + LENGTH;
        save();
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            Notify.toast(o, Text.literal("Ranked Season " + ended + " is over").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(all.isEmpty() ? "Season " + data.number + " begins" : "Champion: " + all.get(0).name + " · Season " + data.number + " begins"),
                0xE0B96A, "minecraft:netherite_sword", null);
            settle(o);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save ranked_season.json", e);
        }
    }
}
