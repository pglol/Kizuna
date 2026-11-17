package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import com.pglol.aotrpg.AotParticles.Fx;

/** Our particles standing in for the vanilla ones the effects used to use. */
public final class Fxs {
    private Fxs() {}

    public static final Fx CLOUD = AotParticles.fx(AotParticles.PUFF, 0xF4F4F4, 1.0f);
    public static final Fx WHITE_SMOKE = AotParticles.fx(AotParticles.PUFF, 0xD8D8D8, 0.85f);
    public static final Fx POOF = AotParticles.fx(AotParticles.PUFF, 0xFFFFFF, 0.7f);
    public static final Fx CAMPFIRE_COSY_SMOKE = AotParticles.fx(AotParticles.PUFF, 0x9A9A9A, 1.6f);
    public static final Fx END_ROD = AotParticles.fx(AotParticles.SPARK, 0xFFFFFF, 0.6f, 12);
    public static final Fx ELECTRIC_SPARK = AotParticles.fx(AotParticles.SPARK, 0xB9C8FF, 0.55f);
    public static final Fx FLASH = AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 5f);
    public static final Fx EXPLOSION = AotParticles.fx(AotParticles.BURST, 0xFFFFFF, 3f);
    public static final Fx EXPLOSION_EMITTER = AotParticles.fx(AotParticles.BURST, 0xFFFFFF, 8f, 14);
    public static final Fx CRIT = AotParticles.fx(AotParticles.SPARK, 0xFFF0C0, 0.45f);
    public static final Fx SWEEP_ATTACK = AotParticles.fx(AotParticles.ARC, 0xFFFFFF, 2.4f);
    public static final Fx FLAME = AotParticles.fx(AotParticles.PUFF, 0xFF9A3A, 0.55f);
    public static final Fx SMALL_FLAME = AotParticles.fx(AotParticles.SPARK, 0xFFB040, 0.4f, 10);
    public static final Fx LAVA = AotParticles.fx(AotParticles.SPARK, 0xFF6A1A, 0.5f, 12);
    public static final Fx SNOWFLAKE = AotParticles.fx(AotParticles.SHARD, 0xDFF4FF, 0.35f);
    public static final Fx SOUL_FIRE_FLAME = AotParticles.fx(AotParticles.PUFF, 0x5AE0FF, 0.55f);
    public static final Fx SOUL = AotParticles.fx(AotParticles.PUFF, 0x7AE8FF, 0.5f);
    public static final Fx REVERSE_PORTAL = AotParticles.fx(AotParticles.PUFF, 0x6A2AA8, 0.6f);
    public static final Fx CHERRY_LEAVES = AotParticles.fx(AotParticles.SHARD, 0xFFB0D0, 0.4f, 30);
    public static final Fx COMPOSTER = AotParticles.fx(AotParticles.SHARD, 0x7AA84A, 0.3f);
    public static final Fx HEART = AotParticles.fx(AotParticles.FLASH, 0xFF5A7A, 1.2f, 14);

    /** A small glowing mote of colour (what coloured dust used to be). */
    public static Fx dot(int rgb, float size) {
        return AotParticles.fx(AotParticles.FLASH, rgb, size * 0.45f, 16);
    }

    public static Fx dot(org.joml.Vector3f c, float size) {
        int rgb = ((int) (c.x * 255) << 16) | ((int) (c.y * 255) << 8) | (int) (c.z * 255);
        return dot(rgb, size);
    }
}
