package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Your ODM cosmetic replaces the gear's own gas: the puffs the ODM gear vents when you boost come
 * out in your style instead (and the plain gas isn't drawn), for everyone wearing one.
 */
public final class GasSwap {
    private GasSwap() {}

    /** Set while our own effects spawn particles (never swapped). */
    static int busy;
    /** When each flier's gas was last swapped (entity -> ms): the speed trail holds off while they boost. */
    private static final Map<Integer, Long> boosted = new HashMap<>();
    private static final Set<ParticleType<?>> GASSY = Set.of(ParticleTypes.CLOUD, ParticleTypes.POOF, ParticleTypes.WHITE_SMOKE, ParticleTypes.SMOKE,
        ParticleTypes.LARGE_SMOKE, ParticleTypes.CAMPFIRE_COSY_SMOKE, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, ParticleTypes.WHITE_ASH);

    public static boolean boosting(int entity) {
        Long t = boosted.get(entity);
        return t != null && net.minecraft.util.Util.getMeasuringTimeMs() - t < 400;
    }

    private static boolean gas(ParticleType<?> t) {
        if (GASSY.contains(t)) return true;
        var id = Registries.PARTICLE_TYPE.getId(t);
        if (id == null || !id.getNamespace().equals("dannys-aot")) return false;
        String p = id.getPath();
        return p.contains("gas") || p.contains("smoke") || p.contains("steam") || p.contains("cloud") || p.contains("odm") || p.contains("puff");
    }

    /** A gas particle about to be added: true if it was replaced by the flier's style. */
    public static boolean swap(ParticleEffect e, double x, double y, double z, double vx, double vy, double vz) {
        if (busy > 0 || e == null || !gas(e.getType())) return false;
        return replace(x, y, z, vx, vy, vz);
    }

    /** The same, for a particle the mod built itself (only its class tells us what it is). */
    public static boolean swap(net.minecraft.client.particle.Particle p) {
        if (busy > 0 || p == null) return false;
        String n = p.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        if (n.startsWith("net.minecraft") || n.startsWith("com.pglol")) return false;
        if (!(n.contains("gas") || n.contains("smoke") || n.contains("steam") || n.contains("odm") || n.contains("cloud"))) return false;
        var c = p.getBoundingBox().getCenter();
        return replace(c.x, c.y, c.z, 0, 0, 0);
    }

    private static boolean replace(double x, double y, double z, double vx, double vy, double vz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        PlayerEntity flier = null;
        double best = 6.5;
        for (PlayerEntity pl : mc.world.getPlayers()) {
            if (pl.isOnGround() || pl.hasVehicle()) continue;
            double d = pl.squaredDistanceTo(x, y - 0.8, z);
            if (d < best) {
                best = d;
                flier = pl;
            }
        }
        if (flier == null) return false;
        String style = CosmeticFx.worn(flier.getUuid(), "odm");
        if (style.isEmpty()) return false;
        boosted.put(flier.getId(), net.minecraft.util.Util.getMeasuringTimeMs());
        if (boosted.size() > 128) boosted.clear();
        CosmeticFx.odmPuff(style, x, y, z, vx, vy, vz);
        return true;
    }
}
