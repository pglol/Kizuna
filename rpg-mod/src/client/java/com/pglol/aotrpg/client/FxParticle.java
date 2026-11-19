package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Our particles: one class, shaped by kind. Streaks stretch along their motion, rings lie flat
 * on the ground and widen, sparks burst and shrink, gas billows and thins, shards tumble and fall,
 * cuts sweep, flashes bloom. All of them glow (drawn full bright).
 */
public final class FxParticle extends SpriteBillboardParticle {
    private final int kind;
    private final float base;
    private Vec3d dir;

    FxParticle(ClientWorld w, double x, double y, double z, double vx, double vy, double vz, AotParticles.Fx fx, SpriteProvider sprites) {
        super(w, x, y, z);
        this.kind = fx.kind();
        this.velocityX = vx;
        this.velocityY = vy;
        this.velocityZ = vz;
        int sprite = switch (kind) {
            case AotParticles.BURST -> AotParticles.RING;
            default -> MathHelper.clamp(kind, 0, 6);
        };
        setSprite(sprites.getSprite(sprite, 6));
        int c = fx.rgb();
        this.red = ((c >> 16) & 255) / 255f;
        this.green = ((c >> 8) & 255) / 255f;
        this.blue = (c & 255) / 255f;
        this.alpha = 1;
        int life = switch (kind) {
            case AotParticles.STREAK -> 7 + random.nextInt(4);
            case AotParticles.SPARK -> 5 + random.nextInt(3);
            case AotParticles.RING, AotParticles.BURST -> 9;
            case AotParticles.PUFF -> 16 + random.nextInt(10);
            case AotParticles.ARC -> 6;
            case AotParticles.SHARD -> 18 + random.nextInt(10);
            default -> 4;
        };
        this.maxAge = fx.life() > 0 ? fx.life() : life;
        this.base = 0.25f * Math.max(0.05f, fx.scale());
        this.scale = base;
        this.gravityStrength = kind == AotParticles.SHARD ? 0.9f : kind == AotParticles.PUFF ? -0.015f : 0;
        this.velocityMultiplier = kind == AotParticles.PUFF ? 0.86f : kind == AotParticles.SHARD ? 0.94f : 0.8f;
        this.collidesWithWorld = kind == AotParticles.SHARD;
        this.angle = this.prevAngle = random.nextFloat() * MathHelper.TAU;
        Vec3d v = new Vec3d(vx, vy, vz);
        this.dir = v.lengthSquared() > 1e-6 ? v.normalize() : new Vec3d(0, 1, 0);
    }

    @Override
    public void tick() {
        prevAngle = angle;
        super.tick();
        float f = age / (float) Math.max(1, maxAge);
        switch (kind) {
            case AotParticles.STREAK -> alpha = 1 - f;
            case AotParticles.SPARK -> {
                scale = base * (1.6f - f);
                alpha = 1 - f * f;
            }
            case AotParticles.RING, AotParticles.BURST -> {
                scale = base * (0.3f + f * 3.2f);
                alpha = (1 - f) * (1 - f);
            }
            case AotParticles.PUFF -> {
                scale = base * (1 + f * 1.8f);
                alpha = 0.8f * (1 - f);
            }
            case AotParticles.ARC -> {
                scale = base * (1 + f * 0.7f);
                alpha = 1 - f;
                angle += 0.25f;
            }
            case AotParticles.SHARD -> {
                alpha = 1 - f * f * f;
                angle += 0.45f;
            }
            default -> {
                scale = base * (1 + f * 1.4f);
                alpha = (1 - f) * (1 - f);
            }
        }
        // Shader packs wash translucent particles out: a little more body under them.
        if (ShaderCompat.packInUse()) alpha = Math.min(1f, alpha * 1.35f);
        Vec3d v = new Vec3d(velocityX, velocityY, velocityZ);
        if (v.lengthSquared() > 1e-5) dir = v.normalize();
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    protected int getBrightness(float tickDelta) {
        return 0xF000F0;
    }

    @Override
    public void buildGeometry(VertexConsumer vc, Camera cam, float td) {
        if (kind != AotParticles.STREAK && kind != AotParticles.RING) {
            super.buildGeometry(vc, cam, td);
            return;
        }
        Vec3d c = cam.getPos();
        float px = (float) (MathHelper.lerp(td, prevPosX, x) - c.x);
        float py = (float) (MathHelper.lerp(td, prevPosY, y) - c.y);
        float pz = (float) (MathHelper.lerp(td, prevPosZ, z) - c.z);
        float s = getSize(td);
        Vector3f p = new Vector3f(px, py, pz);
        Vector3f a, b;
        if (kind == AotParticles.RING) {
            // Flat on the ground, widening.
            a = new Vector3f(s, 0, 0);
            b = new Vector3f(0, 0, s);
            quad(vc, p, a, b, td, false);
            return;
        }
        // A streak: long along its motion, thin across, always facing you.
        Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        Vector3f view = new Vector3f(p).normalize();
        Vector3f side = new Vector3f(d).cross(view);
        if (side.lengthSquared() < 1e-6) side.set(0, 1, 0);
        side.normalize().mul(s * 0.32f);
        Vector3f len = new Vector3f(d).mul(-s * 5.5f);
        // Centre the quad half a length behind the head.
        Vector3f mid = new Vector3f(p).add(new Vector3f(len).mul(0.5f));
        quad(vc, mid, new Vector3f(len).mul(0.5f), side, td, true);
    }

    /** A two-sided quad about centre with half-extents a (u) and b (v). */
    private void quad(VertexConsumer vc, Vector3f c, Vector3f a, Vector3f b, float td, boolean alongU) {
        float u0 = getMinU(), u1 = getMaxU(), v0 = getMinV(), v1 = getMaxV();
        int light = getBrightness(td);
        Vector3f[] q = {
            new Vector3f(c).sub(a).sub(b), new Vector3f(c).sub(a).add(b), new Vector3f(c).add(a).add(b), new Vector3f(c).add(a).sub(b)};
        float[][] uv = alongU ? new float[][] {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}} : new float[][] {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        for (int i = 0; i < 4; i++) vc.vertex(q[i].x, q[i].y, q[i].z).texture(uv[i][0], uv[i][1]).color(red, green, blue, alpha).light(light);
        for (int i = 3; i >= 0; i--) vc.vertex(q[i].x, q[i].y, q[i].z).texture(uv[i][0], uv[i][1]).color(red, green, blue, alpha).light(light);
    }

    public static final class Factory implements ParticleFactory<AotParticles.Fx> {
        private final SpriteProvider sprites;

        public Factory(SpriteProvider sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(AotParticles.Fx fx, ClientWorld w, double x, double y, double z, double vx, double vy, double vz) {
            // Kept light: little ones far away aren't made at all, and fewer on lower particle settings.
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            double d2 = mc.gameRenderer.getCamera().getPos().squaredDistanceTo(x, y, z);
            boolean big = fx.kind() == AotParticles.RING || fx.kind() == AotParticles.BURST || fx.kind() == AotParticles.FLASH;
            if (!big && d2 > 56 * 56) return null;
            var setting = mc.options.getParticles().getValue();
            if (!big && setting == net.minecraft.client.option.ParticlesMode.DECREASED && w.random.nextInt(2) == 0) return null;
            if (!big && setting == net.minecraft.client.option.ParticlesMode.MINIMAL && w.random.nextInt(4) != 0) return null;
            return new FxParticle(w, x, y, z, vx, vy, vz, fx, sprites);
        }
    }

    // ------------------------------------------------------------------ client helpers

    public static void spawn(int kind, int rgb, float scale, double x, double y, double z, double vx, double vy, double vz) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.world == null) return;
        GasSwap.busy++;
        try {
            mc.world.addImportantParticle(AotParticles.fx(kind, rgb, scale), true, x, y, z, vx, vy, vz);
        } finally {
            GasSwap.busy--;
        }
    }
}
