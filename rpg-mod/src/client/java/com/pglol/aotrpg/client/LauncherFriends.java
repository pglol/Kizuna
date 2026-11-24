package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Keeps a copy of your in-game friends (their Minecraft names) in config/kizuna/friends.json, where
 * the Kizuna launcher picks it up: friends made here then show in the launcher, with whichever of
 * our servers they're playing on.
 */
public final class LauncherFriends {
    private LauncherFriends() {}

    public static void save(Net.SocialView v) {
        Set<String> names = new LinkedHashSet<>();
        for (Net.SocialPlayer p : v.players()) {
            if (p.friend() && p.account() != null && p.account().matches("[A-Za-z0-9_]{3,16}")) names.add(p.account());
        }
        try {
            Path f = FabricLoader.getInstance().getConfigDir().resolve("kizuna").resolve("friends.json");
            Files.createDirectories(f.getParent());
            StringBuilder s = new StringBuilder("{\"server\":\"aot\",\"friends\":[");
            int i = 0;
            for (String n : names) s.append(i++ == 0 ? "" : ",").append('"').append(n).append('"');
            Files.writeString(f, s.append("]}").toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
