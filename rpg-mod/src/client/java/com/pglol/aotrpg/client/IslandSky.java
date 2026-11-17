package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;

/**
 * The extraction map cycle on your screen: an island at night, under a storm or in grey rain,
 * whatever the overworld's own clock and weather are doing. Held while you're on that island.
 */
public final class IslandSky {
    private IslandSky() {}

    private static Net.IslandSky now;
    private static float rain, thunder;

    public static void on(Net.IslandSky s) {
        now = s;
    }

    /** The hour this world should show, if it's the island we're on (null otherwise). */
    public static Long hour(net.minecraft.client.world.ClientWorld w) {
        Net.IslandSky n = now;
        if (n == null || !w.getRegistryKey().getValue().toString().equals(n.world())) return null;
        return (long) n.time();
    }

    public static void tick(MinecraftClient mc) {
        if (now == null || mc.world == null) return;
        if (!mc.world.getRegistryKey().getValue().toString().equals(now.world())) {
            now = null;
            return;
        }
        mc.world.setTimeOfDay(now.time());
        // Ease into the weather rather than snapping.
        rain += ((now.rain() ? 1f : 0f) - rain) * 0.05f;
        thunder += ((now.thunder() ? 1f : 0f) - thunder) * 0.05f;
        mc.world.setRainGradient(rain);
        mc.world.setThunderGradient(thunder);
    }
}
