package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Falling for real (a long drop, not a hop): wind tears up past you in streams, and the third-person
 * camera eases slowly back to show the height.
 */
public final class FallFx {
    private FallFx() {}

    private static int falling;
    private static float k;

    /** How far into a long fall (0 .. 1): pulls the camera back. */
    public static float amount() {
        return k;
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            k = 0;
            return;
        }
        double vy = p.getVelocity().y;
        boolean fall = !p.isOnGround() && !p.isTouchingWater() && !p.isFallFlying() && !p.hasVehicle() && !p.getAbilities().flying && vy < -0.5;
        falling = fall ? falling + 1 : 0;
        boolean on = falling > 10;
        // Eases out slowly as the fall goes on, back in quickly on landing.
        k = MathHelper.clamp(k + (on ? 0.012f : -0.08f), 0, 1);
        if (!on) return;
        var r = mc.world.random;
        int n = 1 + (int) Math.min(3, -vy * 1.5);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 0.8 + r.nextDouble() * 1.2;
            FxParticle.spawn(com.pglol.aotrpg.AotParticles.STREAK, 0xEAF4FF, 0.5f + r.nextFloat() * 0.4f,
                p.getX() + Math.cos(a) * d, p.getY() - 1 + r.nextDouble() * 3, p.getZ() + Math.sin(a) * d,
                0, -vy * 0.9 + 0.2, 0);
        }
    }
}
