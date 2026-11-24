package com.pglol.aotrpg.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pglol.aotrpg.Discipline;
import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.Origin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * The pack's title screen (Kizuna branding) shows your account and characters before you join. This
 * keeps the copy it reads in config/kizuna/profile.json up to date, and when you picked a character
 * there, plays as that one straight away instead of opening the character select screen.
 */
public final class LauncherProfile {
    private LauncherProfile() {}

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("kizuna");
    }

    /** Called with each character list from the server. Returns true when it handled the pick itself. */
    public static boolean onList(Net.CharacterList v) {
        save(v);
        if (!v.open()) return false;
        Path pick = dir().resolve("pick.txt");
        if (!Files.exists(pick)) return false;
        try {
            int slot = Integer.parseInt(Files.readString(pick, StandardCharsets.UTF_8).trim());
            Files.deleteIfExists(pick);
            for (Net.CharacterEntry e : v.list()) {
                if (e.slot() == slot && e.created()) {
                    ClientPlayNetworking.send(new Net.CharacterAction("play", slot));
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void save(Net.CharacterList v) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
            JsonObject root = new JsonObject();
            root.addProperty("account", mc.getSession().getUsername());
            int made = 0, best = 0;
            JsonArray chars = new JsonArray();
            for (Net.CharacterEntry e : v.list()) {
                if (!e.created()) continue;
                made++;
                best = Math.max(best, e.level());
                StringBuilder sub = new StringBuilder("Lv " + e.level());
                if (e.discipline() >= 0 && e.discipline() < Discipline.values().length) sub.append(" · ").append(Discipline.values()[e.discipline()].title);
                if (e.origin() >= 0 && e.origin() < Origin.values().length) sub.append(" · ").append(Origin.values()[e.origin()].title);
                JsonObject c = new JsonObject();
                c.addProperty("slot", e.slot());
                c.addProperty("name", e.name());
                c.addProperty("sub", sub.toString());
                c.addProperty("active", e.slot() == v.active());
                c.addProperty("lastPlayed", e.lastPlayed());
                chars.add(c);
            }
            JsonArray lines = new JsonArray();
            lines.add(line("Characters", made + " / " + v.max()));
            lines.add(line("Highest level", String.valueOf(best)));
            lines.add(line("Marks", nf.format(ClientState.marks)));
            root.add("lines", lines);
            root.add("characters", chars);
            root.addProperty("updated", System.currentTimeMillis());
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("profile.json"), root.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static JsonArray line(String k, String v) {
        JsonArray a = new JsonArray();
        a.add(k);
        a.add(v);
        return a;
    }
}
