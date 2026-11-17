package com.pglol.aotrpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * Our own particles, drawn from our own textures: wind streaks stretched along their motion,
 * impact sparks, shock rings flat on the ground, soft gas, crescent cuts, steel shards and flashes.
 * One type carries the shape, colour, size and life, so the server can send any of them.
 */
public final class AotParticles {
    private AotParticles() { }

    public static final int STREAK = 0, SPARK = 1, RING = 2, PUFF = 3, ARC = 4, SHARD = 5, FLASH = 6, BURST = 7;

    public record Fx(int kind, int rgb, float scale, int life) implements ParticleEffect {
        public static final MapCodec<Fx> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.fieldOf("kind").forGetter(Fx::kind),
            Codec.INT.fieldOf("rgb").forGetter(Fx::rgb),
            Codec.FLOAT.fieldOf("scale").forGetter(Fx::scale),
            Codec.INT.fieldOf("life").forGetter(Fx::life)).apply(i, Fx::new));
        public static final PacketCodec<RegistryByteBuf, Fx> PACKET = PacketCodec.tuple(
            PacketCodecs.VAR_INT, Fx::kind, PacketCodecs.INTEGER, Fx::rgb, PacketCodecs.FLOAT, Fx::scale, PacketCodecs.VAR_INT, Fx::life, Fx::new);

        @Override
        public ParticleType<?> getType() {
            return FX;
        }
    }

    public static ParticleType<Fx> FX;

    public static void register() {
        FX = Registry.register(Registries.PARTICLE_TYPE, Identifier.of("aot_rpg", "fx"), FabricParticleTypes.complex(true, Fx.CODEC, Fx.PACKET));
    }

    public static Fx fx(int kind, int rgb, float scale, int life) {
        return new Fx(kind, rgb, scale, life);
    }

    public static Fx fx(int kind, int rgb, float scale) {
        return new Fx(kind, rgb, scale, 0);
    }

    /** Sends a burst from the server: count particles spread about the spot, at speed. */
    public static void burst(ServerWorld w, int kind, int rgb, float scale, double x, double y, double z, int count, double spread, double speed) {
        w.spawnParticles(fx(kind, rgb, scale), x, y, z, count, spread, spread, spread, speed);
    }

    /** A single one, moving exactly as given (count 0 makes the offsets a velocity). */
    public static void one(ServerWorld w, Fx fx, double x, double y, double z, double vx, double vy, double vz) {
        w.spawnParticles(fx, x, y, z, 0, vx, vy, vz, 1);
    }
}
