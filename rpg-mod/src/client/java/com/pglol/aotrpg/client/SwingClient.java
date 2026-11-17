package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads each blade swing's direction from how your view was moving as you swung (a sweep left or
 * right, a chop down, a rising cut, or a thrust when you hold still), tells everyone, and times
 * each body's swing by its direction (see Swings).
 */
public final class SwingClient {
    private SwingClient() {}

    private static float[] speed = {1, 1, 1, 1, 1};
    private static final Map<Integer, Integer> dirs = new HashMap<>();
    private static final float[] yaws = new float[4], pitches = new float[4];
    private static int at;
    private static boolean attackWas;

    public static void on(Net.SwingSpeeds s) {
        if (s.speed().length >= 5) speed = s.speed();
    }

    public static void seen(Net.SwingSeen s) {
        dirs.put(s.entity(), s.dir());
        if (dirs.size() > 256) dirs.clear();
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        if (pl == null) return;
        yaws[at % 4] = pl.getYaw();
        pitches[at % 4] = pl.getPitch();
        at++;
        boolean atk = mc.options.attackKey.isPressed() && mc.currentScreen == null;
        boolean press = atk && !attackWas;
        attackWas = atk;
        if (!press || !com.pglol.aotrpg.Loadout.isMelee(pl.getMainHandStack())) return;
        // How the view moved over the last few ticks.
        float dy = MathHelper.wrapDegrees(yaws[(at - 1) % 4] - yaws[at % 4]), dp = pitches[(at - 1) % 4] - pitches[at % 4];
        int dir;
        if (Math.abs(dy) < 4 && Math.abs(dp) < 4) dir = 4;
        else if (Math.abs(dy) >= Math.abs(dp)) dir = dy > 0 ? 1 : 0;
        else dir = dp > 0 ? 2 : 3;
        dirs.put(pl.getId(), dir);
        ClientPlayNetworking.send(new Net.SwingDir(dir));
    }

    /** How long this body's swing takes, scaled by its direction. */
    public static int duration(int entity, int base) {
        Integer d = dirs.get(entity);
        if (d == null || d < 0 || d >= speed.length) return base;
        return Math.max(1, Math.round(base * speed[d]));
    }
}
