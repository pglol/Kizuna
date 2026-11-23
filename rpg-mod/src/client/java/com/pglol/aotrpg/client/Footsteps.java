package com.pglol.aotrpg.client;

import com.pglol.aotrpg.HitSounds;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Boot footsteps for every player we can see, yourself included: a heel and a toe roll on stone,
 * timber, earth or gravel, paced by stride, softer creeping, harder running, and a heavy landing
 * after a drop. Played here so they're never lost to a server or another mod.
 */
public final class Footsteps {
    private Footsteps() { }

    private static final class Gait {
        double gone, lastX, lastZ, fallFrom;
        boolean ground = true;
        boolean left;
    }

    private static final Map<UUID, Gait> gaits = new HashMap<>();

    public static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || mc.isPaused()) return;
        if (gaits.size() > 200) gaits.clear();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.isSpectator() || p.squaredDistanceTo(mc.player) > 48 * 48) continue;
            Gait g = gaits.computeIfAbsent(p.getUuid(), k -> {
                Gait n = new Gait();
                n.lastX = p.getX();
                n.lastZ = p.getZ();
                n.fallFrom = p.getY();
                return n;
            });
            double dx = p.getX() - g.lastX, dz = p.getZ() - g.lastZ;
            g.lastX = p.getX();
            g.lastZ = p.getZ();
            double moved = Math.sqrt(dx * dx + dz * dz);
            if (moved > 3) moved = 0; // teleported or reeled in by the gear: not walking
            boolean ground = p.isOnGround() && !p.hasVehicle() && !p.isTouchingWater() && !p.isFallFlying();
            if (!ground) {
                if (g.ground) g.fallFrom = p.getY();
                g.fallFrom = Math.max(g.fallFrom, p.getY());
                g.ground = false;
                continue;
            }
            if (!g.ground) {
                g.ground = true;
                double drop = g.fallFrom - p.getY();
                if (drop > 1.2) {
                    // Both feet hit together.
                    float v = (float) Math.min(1.1, 0.45 + drop * 0.08);
                    play(mc, p, v, 0.82f);
                    play(mc, p, v * 0.7f, 0.9f);
                    g.gone = 0;
                    continue;
                }
            }
            g.gone += moved;
            boolean sprint = p.isSprinting(), creep = p.isSneaking();
            double stride = sprint ? 2.0 : creep ? 1.0 : 1.55;
            if (g.gone < stride) continue;
            g.gone = 0;
            g.left = !g.left;
            float vol = sprint ? 0.6f : creep ? 0.12f : 0.36f;
            if (p == mc.player) vol *= 0.8f;
            play(mc, p, vol, (sprint ? 0.97f : 1f) * (g.left ? 1f : 1.03f));
        }
    }

    private static void play(MinecraftClient mc, PlayerEntity p, float vol, float pitch) {
        BlockPos at = p.getSteppingPos();
        BlockState st = mc.world.getBlockState(p.getBlockPos());
        // Carpet, snow and the like sit on top of what's below: step on those first.
        if (st.isAir() || st.getCollisionShape(mc.world, p.getBlockPos()).isEmpty() && !st.isOf(net.minecraft.block.Blocks.SNOW)) st = mc.world.getBlockState(at);
        if (st.isAir()) return;
        pitch *= 0.95f + p.getRandom().nextFloat() * 0.1f;
        mc.world.playSound(p.getX(), p.getY(), p.getZ(), HitSounds.step(st), SoundCategory.PLAYERS, vol, pitch, false);
    }

    public static void reset() {
        gaits.clear();
    }
}
