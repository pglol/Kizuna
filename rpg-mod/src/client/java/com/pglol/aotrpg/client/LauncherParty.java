package com.pglol.aotrpg.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Your party from the Kizuna launcher (it keeps config/kizuna/party.json up to date while you play).
 * The server is told who's in it, so friends who come here together land in the same party; the party
 * HUD lists the members who aren't on this server (in the launcher, playing something else, offline).
 */
public final class LauncherParty {
    private LauncherParty() {}

    public record Member(String name, boolean online, String state, String game) { }

    public static volatile String you = "", leader = "";
    public static volatile List<Member> members = List.of();
    private static String sent = null;
    private static long stamp = -1;
    private static int tick;

    /** Joined a server: tell it again. */
    public static void joined() {
        sent = null;
        tick = 0;
    }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || ++tick % 40 != 1) return;
        Path f = FabricLoader.getInstance().getConfigDir().resolve("kizuna").resolve("party.json");
        try {
            long m = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            if (m != stamp) {
                stamp = m;
                read(f, m);
            }
        } catch (Exception ignored) {
        }
        String key = leader + "|" + String.join(",", names());
        if (!key.equals(sent) && ClientPlayNetworking.canSend(Net.LauncherParty.ID)) {
            sent = key;
            ClientPlayNetworking.send(new Net.LauncherParty(leader, names()));
        }
    }

    private static void read(Path f, long m) throws Exception {
        if (m == 0) {
            leader = "";
            members = List.of();
            return;
        }
        JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
        you = o.has("you") ? o.get("you").getAsString() : "";
        leader = o.has("leader") ? o.get("leader").getAsString() : "";
        List<Member> list = new ArrayList<>();
        JsonArray a = o.has("members") ? o.getAsJsonArray("members") : new JsonArray();
        for (JsonElement e : a) {
            JsonObject x = e.getAsJsonObject();
            list.add(new Member(x.get("name").getAsString(), x.has("online") && x.get("online").getAsBoolean(),
                x.has("state") ? x.get("state").getAsString() : "offline", x.has("game") ? x.get("game").getAsString() : ""));
        }
        members = list;
    }

    private static List<String> names() {
        List<String> n = new ArrayList<>();
        for (Member m : members) n.add(m.name());
        return n;
    }

    /** Party members who aren't on this server, with what they're doing. */
    public static List<String[]> elsewhere(MinecraftClient mc) {
        List<String[]> out = new ArrayList<>();
        if (members.isEmpty() || mc.getNetworkHandler() == null) return out;
        for (Member m : members) {
            if (m.name().equalsIgnoreCase(you)) continue;
            if (mc.getNetworkHandler().getPlayerListEntry(m.name()) != null) continue;
            String what = !m.online() ? "Offline" : "playing".equals(m.state()) ? "Playing " + (m.game().isEmpty() ? "elsewhere" : m.game()) : "In the launcher";
            out.add(new String[] {m.name(), what, m.name().equalsIgnoreCase(leader) ? "1" : ""});
        }
        return out;
    }
}
