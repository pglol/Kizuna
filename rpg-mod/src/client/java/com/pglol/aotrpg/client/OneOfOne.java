package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * The 1 of 1 sets, drawn. Two of them:
 * <ul>
 *   <li><b>Spiritual Pressure</b> (_sp), in the colour its owner picks: an aura that burns up off
 *   the whole body in tongues of light with a pulse of pressure at the feet, glowing eyes that
 *   streak when you move, a mantle of light flowing off the back, a black robe with glowing trim,
 *   crescent-fang slashes that fly off the blade, rounds that spiral light, a pillar of pressure
 *   into the sky on a kill;</li>
 *   <li><b>Avenger</b> (_uch): lightning crackling in the right hand and black flames licking up
 *   around you, red eyes, a spectral warrior's purple ribcage around the body, chidori-edged
 *   slashes, bolts for rounds, black flames that swallow what you kill.</li>
 * </ul>
 */
public final class OneOfOne {
    private OneOfOne() {}

    static final int LIGHT = 0x9FD8FF, PURPLE = 0x7A3AD0, BLACK = 0x0A0610, RED = 0xE01A1A;
    static final int DEFAULT_TINT = 0x6AC8FF;
    /** How far the pressure reaches along the ground, and how far away you still feel it. */
    static final float FIELD = 6.5f, FELT = 14f;

    // ------------------------------------------------------------------ the pressure, in the world

    /**
     * Drawn in the world (glowing geometry, so it reads under shaders too): a field of pressure
     * over the ground around its owner, shock rings running out across it and a slow ring of
     * marks turning in it, the aura hugging the body in flickering tongues; and the column, a
     * pillar of it straight up into the sky.
     */
    public static void renderWorld(MatrixStack ms, VertexConsumerProvider vc, Vec3d cam, float td, float time) {
        var mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.isInvisible() || p.isSpectator() || p.squaredDistanceTo(cam) > 64 * 64) continue;
            String body = CosmeticFx.worn(p.getUuid(), "body"), back = CosmeticFx.worn(p.getUuid(), "back");
            boolean field = body.equals("body_sp"), column = back.equals("back_sp");
            if (!field && !column) continue;
            int tint = tint(p.getUuid());
            Vec3d pos = p.getLerpedPos(td);
            // Where the ground is under them (it thins out the higher up they are).
            double gy = pos.y;
            float lift = 1;
            if (!p.isOnGround()) {
                var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(pos, pos.add(0, -12, 0), net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
                if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) lift = 0;
                else {
                    gy = hit.getPos().y;
                    lift = (float) Math.max(0, 1 - (pos.y - gy) / 12);
                }
            }
            VertexConsumer glow = vc.getBuffer(RenderLayer.getLightning());
            boolean firstPerson = p == mc.player && mc.options.getPerspective().isFirstPerson();
            if (field && lift > 0.02f) {
                // Faint on the ground under it: the air there is thick with it.
                ms.push();
                ms.translate(pos.x - cam.x, gy - cam.y + 0.04, pos.z - cam.z);
                Matrix4f m = ms.peek().getPositionMatrix();
                float breathe = 1 + 0.05f * MathHelper.sin(time * 2.1f);
                disc(glow, m, 3.2f * breathe, (int) (0x34 * lift) << 24 | tint, tint & 0xFFFFFF);
                for (int i = 0; i < 2; i++) {
                    float k = (time * 0.45f + i / 2f) % 1f;
                    ring(glow, m, 0.5f + k * 4.5f, 0.06f + 0.08f * k, (int) (0x70 * (1 - k) * lift) << 24 | lighter(tint, 0.3f));
                }
                ms.pop();
            }
            if (!firstPerson && (field || column)) {
                ms.push();
                ms.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
                Matrix4f m = ms.peek().getPositionMatrix();
                Vec3d view = pos.subtract(cam);
                float sneak = p.isInSneakingPose() ? 0.8f : 1f;
                long seed = p.getUuid().getLeastSignificantBits();
                // The aura: flame tongues all round the body, rising, swaying and gone, the
                // brightest nearest the body, thinner and wilder further out.
                if (field) {
                    wisps(glow, m, view, time, seed, 30, 0.2f, 0.55f, 2.2f * sneak, 0.55f, lighter(tint, 0.45f), 0xB0, 1.3f);
                    wisps(glow, m, view, time, seed + 7, 26, 0.45f, 1.0f, 2.6f * sneak, 0.75f, tint, 0x80, 1f);
                    sparkles(glow, m, view, time, seed, 14, 1.1f, 2.6f);
                }
                // Released: towering flames billowing up well over the head.
                if (column) {
                    wisps(glow, m, view, time * 0.8f, seed + 13, 22, 0.5f, 1.5f, 5.5f * sneak, 1.5f, tint, 0x70, 0.7f);
                    wisps(glow, m, view, time * 0.8f, seed + 29, 14, 0.3f, 0.9f, 4f * sneak, 1.1f, lighter(tint, 0.55f), 0x60, 0.9f);
                    sparkles(glow, m, view, time, seed + 3, 10, 1.6f, 5f);
                }
                ms.pop();
            }
        }
    }

    private static float hash(long seed, int i, int k) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L + k * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        h *= 0x7FB5D329728EA185L;
        h ^= h >>> 27;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    /**
     * n flame tongues, each living its own short life: born low in a ring between rMin and rMax,
     * rising to height, swelling then thinning to a point, swaying, fading. Drawn as soft-edged
     * flame shapes facing the camera (bright at the heart, nothing at the edge).
     */
    private static void wisps(VertexConsumer vc, Matrix4f m, Vec3d view, float time, long seed, int n, float rMin, float rMax, float height,
                              float size, int col, int alpha, float speed) {
        Vec3d flat = new Vec3d(view.x, 0, view.z);
        Vec3d right = flat.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : new Vec3d(-flat.z, 0, flat.x).normalize();
        for (int i = 0; i < n; i++) {
            float rate = (0.6f + hash(seed, i, 1) * 0.7f) * speed;
            float u = (time * rate + hash(seed, i, 2)) % 1f;
            int gen = (int) Math.floor(time * rate + hash(seed, i, 2));
            // A fresh spot each time it's reborn.
            double a = hash(seed, i * 31 + gen, 3) * Math.PI * 2;
            float r = rMin + (rMax - rMin) * hash(seed, i * 17 + gen, 4);
            float sway = MathHelper.sin(time * 3.1f + i) * 0.08f + (float) Math.sin(u * Math.PI * 2 + i) * 0.05f;
            float x = (float) Math.cos(a) * r + (float) right.x * sway, z = (float) Math.sin(a) * r + (float) right.z * sway;
            float y = -0.1f + u * height * (0.55f + 0.45f * hash(seed, i, 5));
            float life = MathHelper.sin(u * MathHelper.PI);
            float w = size * (0.18f + 0.22f * hash(seed, i, 6)) * life, h = w * (2.6f + 1.4f * hash(seed, i, 7));
            int ca = (int) (alpha * life * life);
            if (ca < 4 || w < 0.01f) continue;
            flame(vc, m, x, y, z, right, w, h, ca << 24 | col);
        }
    }

    /** A soft flame shape facing the camera: a rounded base, drawn up to a point; bright heart, clear edge. */
    private static void flame(VertexConsumer vc, Matrix4f m, float x, float y, float z, Vec3d right, float w, float h, int argb) {
        int edge = argb & 0xFFFFFF;
        float cx = x, cy = y + h * 0.3f, cz = z;
        int seg = 10;
        float[] px = new float[seg + 1], py = new float[seg + 1];
        for (int k = 0; k <= seg; k++) {
            // Around the outline: from the tip, down one side, round the base, up the other.
            double t = k / (double) seg * Math.PI * 2;
            float sx = (float) Math.sin(t), cyy = (float) Math.cos(t);
            float up = cyy > 0 ? cyy * h * 0.7f : cyy * w * 0.9f;
            float side = sx * w * (cyy > 0 ? (1 - cyy) * 0.9f + 0.1f : 1);
            px[k] = side;
            py[k] = up;
        }
        for (int k = 0; k < seg; k++) {
            float ax = cx + (float) right.x * px[k], az = cz + (float) right.z * px[k], ay = cy + py[k];
            float bx = cx + (float) right.x * px[k + 1], bz = cz + (float) right.z * px[k + 1], by = cy + py[k + 1];
            vc.vertex(m, cx, cy, cz).color(argb);
            vc.vertex(m, ax, ay, az).color(edge);
            vc.vertex(m, bx, by, bz).color(edge);
            vc.vertex(m, cx, cy, cz).color(argb);
            vc.vertex(m, cx, cy, cz).color(argb);
            vc.vertex(m, bx, by, bz).color(edge);
            vc.vertex(m, ax, ay, az).color(edge);
            vc.vertex(m, cx, cy, cz).color(argb);
        }
    }

    /** Tiny four-pointed glints drifting up through it. */
    private static void sparkles(VertexConsumer vc, Matrix4f m, Vec3d view, float time, long seed, int n, float radius, float height) {
        Vec3d flat = new Vec3d(view.x, 0, view.z);
        Vec3d right = flat.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : new Vec3d(-flat.z, 0, flat.x).normalize();
        for (int i = 0; i < n; i++) {
            float rate = 0.35f + hash(seed, i, 8) * 0.4f;
            float u = (time * rate + hash(seed, i, 9)) % 1f;
            int gen = (int) Math.floor(time * rate + hash(seed, i, 9));
            double a = hash(seed, i * 13 + gen, 10) * Math.PI * 2;
            float r = 0.3f + radius * hash(seed, i * 7 + gen, 11);
            float x = (float) Math.cos(a) * r, z = (float) Math.sin(a) * r, y = 0.2f + u * height;
            float tw = MathHelper.sin(u * MathHelper.PI) * (0.6f + 0.4f * MathHelper.sin(time * 14 + i));
            float s = 0.09f * tw;
            if (s < 0.01f) continue;
            int c = (int) (0xF0 * tw) << 24 | 0xFFFFFF;
            float rx = (float) right.x, rz = (float) right.z;
            // Two thin crossed diamonds.
            vc.vertex(m, x, y + s * 2, z).color(c);
            vc.vertex(m, x + rx * s * 0.3f, y, z + rz * s * 0.3f).color(c & 0xFFFFFF);
            vc.vertex(m, x, y - s * 2, z).color(c);
            vc.vertex(m, x - rx * s * 0.3f, y, z - rz * s * 0.3f).color(c & 0xFFFFFF);
            vc.vertex(m, x - rx * s * 2, y, z - rz * s * 2).color(c);
            vc.vertex(m, x, y + s * 0.3f, z).color(c & 0xFFFFFF);
            vc.vertex(m, x + rx * s * 2, y, z + rz * s * 2).color(c);
            vc.vertex(m, x, y - s * 0.3f, z).color(c & 0xFFFFFF);
        }
    }

    private static void disc(VertexConsumer vc, Matrix4f m, float r, int inner, int outer) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float x0 = (float) Math.cos(a0) * r, z0 = (float) Math.sin(a0) * r, x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r;
            vc.vertex(m, 0, 0, 0).color(inner);
            vc.vertex(m, x0, 0, z0).color(outer);
            vc.vertex(m, x1, 0, z1).color(outer);
            vc.vertex(m, 0, 0, 0).color(inner);
            vc.vertex(m, 0, 0, 0).color(inner);
            vc.vertex(m, x1, 0, z1).color(outer);
            vc.vertex(m, x0, 0, z0).color(outer);
            vc.vertex(m, 0, 0, 0).color(inner);
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f m, float r, float w, int argb) {
        arcSeg(vc, m, r, w, 0, Math.PI * 2, argb);
    }

    private static void arcSeg(VertexConsumer vc, Matrix4f m, float r, float w, double from, double to, int argb) {
        int n = Math.max(2, (int) ((to - from) / (Math.PI * 2) * 48));
        for (int i = 0; i < n; i++) {
            double a0 = from + (to - from) * i / n, a1 = from + (to - from) * (i + 1) / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ri = r - w / 2, ro = r + w / 2;
            vc.vertex(m, c0 * ri, 0, s0 * ri).color(argb);
            vc.vertex(m, c0 * ro, 0, s0 * ro).color(argb);
            vc.vertex(m, c1 * ro, 0, s1 * ro).color(argb);
            vc.vertex(m, c1 * ri, 0, s1 * ri).color(argb);
            vc.vertex(m, c1 * ri, 0, s1 * ri).color(argb);
            vc.vertex(m, c1 * ro, 0, s1 * ro).color(argb);
            vc.vertex(m, c0 * ro, 0, s0 * ro).color(argb);
            vc.vertex(m, c0 * ri, 0, s0 * ri).color(argb);
        }
    }

    /** An upright panel between two points on the ground, its top at its own heights, fading up. */
    private static void wall(VertexConsumer vc, Matrix4f m, float x0, float z0, float x1, float z1, float y, float h0, float h1, int bottom, int top) {
        vc.vertex(m, x0, y, z0).color(bottom);
        vc.vertex(m, x1, y, z1).color(bottom);
        vc.vertex(m, x1, y + h1, z1).color(top);
        vc.vertex(m, x0, y + h0, z0).color(top);
        vc.vertex(m, x0, y + h0, z0).color(top);
        vc.vertex(m, x1, y + h1, z1).color(top);
        vc.vertex(m, x1, y, z1).color(bottom);
        vc.vertex(m, x0, y, z0).color(bottom);
    }

    // ------------------------------------------------------------------ feeling it

    private static long nextWeight;

    /**
     * Standing near someone giving off pressure: you feel it. The edges of your sight press in,
     * in their colour, pulsing with it, and now and then the weight of it shudders through you.
     */
    public static void hud(net.minecraft.client.gui.DrawContext c, net.minecraft.client.render.RenderTickCounter tick) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        PlayerEntity src = null;
        double best = FELT;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.isSpectator() || !CosmeticFx.worn(p.getUuid(), "body").equals("body_sp")) continue;
            double d = p.distanceTo(mc.player);
            if (d < best) {
                best = d;
                src = p;
            }
        }
        if (src == null) return;
        boolean own = src == mc.player;
        float k = own ? 0.35f : (float) (1 - best / FELT);
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        float pulse = 0.7f + 0.3f * MathHelper.sin(now / 420f);
        int tint = tint(src.getUuid());
        int a = (int) (0x70 * k * pulse);
        int W = c.getScaledWindowWidth(), H = c.getScaledWindowHeight(), e = (int) (H * (0.12f + 0.12f * k));
        c.fillGradient(0, 0, W, e, a << 24 | tint, tint & 0xFFFFFF);
        c.fillGradient(0, H - e, W, H, tint & 0xFFFFFF, a << 24 | tint);
        if (!own && now > nextWeight) {
            nextWeight = now + 2200 + (long) (Math.random() * 1500);
            EffectFx.quake(0.08f + 0.3f * k, 8);
        }
    }

    public static String base(String s) {
        int i = s == null ? -1 : s.indexOf('#');
        return s == null ? "" : i < 0 ? s : s.substring(0, i);
    }

    public static boolean ours(String s) {
        String b = base(s);
        return b.endsWith("_sp") || b.endsWith("_uch") || b.equals("outfit_sp_robe");
    }

    static boolean sp(String s) {
        String b = base(s);
        return b.endsWith("_sp") || b.equals("outfit_sp_robe");
    }

    /** The colour carried with an effect ("id#RRGGBB"), else the default. */
    static int tintOf(String s) {
        int i = s == null ? -1 : s.indexOf('#');
        if (i < 0) return DEFAULT_TINT;
        try {
            return Integer.parseInt(s.substring(i + 1), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return DEFAULT_TINT;
        }
    }

    /** A player's chosen colour. */
    public static int tint(UUID id) {
        String t = CosmeticFx.worn(id, "tint");
        if (t == null || t.isEmpty()) return DEFAULT_TINT;
        try {
            return Integer.parseInt(t, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return DEFAULT_TINT;
        }
    }

    static int lighter(int c, float k) {
        return Showcase.mix(c, 0xFFFFFF, k);
    }

    private static double rr() {
        return Math.random() - 0.5;
    }

    private static void fx(int kind, int rgb, float scale, double x, double y, double z, double vx, double vy, double vz) {
        FxParticle.spawn(kind, rgb, scale, x, y, z, vx, vy, vz);
    }

    // ------------------------------------------------------------------ always on

    /** Every client tick, for each player near: their aura, eyes, mantle and hooves. */
    public static void tickPlayer(MinecraftClient mc, PlayerEntity p, boolean me, long t) {
        String body = CosmeticFx.worn(p.getUuid(), "body"), head = CosmeticFx.worn(p.getUuid(), "head"), back = CosmeticFx.worn(p.getUuid(), "back");
        boolean any = ours(body) || ours(head) || ours(back) || ours(CosmeticFx.worn(p.getUuid(), "horse"));
        if (!any) return;
        int tint = tint(p.getUuid());
        double yaw = Math.toRadians(p.bodyYaw);
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d v = p.getPos().subtract(p.prevX, p.prevY, p.prevZ);
        double speed = v.length();
        double x = p.getX(), y = p.getY(), z = p.getZ();
        if (body.equals("body_sp") && p.isOnGround()) {
            // The ground feels it: grit and pebbles shiver up all through the field.
            if (t % 3 == 0) {
                double a = Math.random() * Math.PI * 2, r = 1 + Math.random() * (FIELD - 1);
                fx(AotParticles.SHARD, 0x8A8070, 0.25f, x + Math.cos(a) * r, y + 0.05, z + Math.sin(a) * r, 0, 0.18 + Math.random() * 0.1, 0);
            }
            if (!me && t % 4 == 0) fx(AotParticles.SPARK, lighter(tint, 0.6f), 0.3f, x + rr() * 0.9, y + Math.random() * 2, z + rr() * 0.9, 0, 0.12, 0);
        }
        if (!me && body.equals("body_uch")) {
            // Lightning in the right hand...
            Vec3d hand = p.getPos().add(right.multiply(0.38)).add(fwd.multiply(0.12)).add(0, p.isSneaking() ? 0.5 : 0.68, 0);
            for (int i = 0; i < 2; i++) fx(AotParticles.SPARK, i == 0 ? LIGHT : 0xFFFFFF, 0.32f, hand.x, hand.y, hand.z, rr() * 0.22, rr() * 0.22, rr() * 0.22);
            if (t % 3 == 0) fx(AotParticles.STREAK, 0xEAF6FF, 0.32f, hand.x + rr() * 0.2, hand.y + rr() * 0.2, hand.z + rr() * 0.2, rr() * 0.6, rr() * 0.6, rr() * 0.6);
            if (t % 37 == Math.floorMod(p.getId(), 37)) fx(AotParticles.FLASH, LIGHT, 0.9f, hand.x, hand.y, hand.z, 0, 0, 0);
            // ...and black flames licking up around you.
            if (t % 2 == 0) {
                double a = Math.random() * Math.PI * 2, r = 0.3 + Math.random() * 0.3;
                fx(AotParticles.PUFF, BLACK, 0.7f + (float) Math.random() * 0.4f, x + Math.cos(a) * r, y + Math.random() * 0.9, z + Math.sin(a) * r, 0, 0.05, 0);
            }
            if (t % 6 == 0) fx(AotParticles.PUFF, PURPLE, 0.45f, x + rr() * 0.6, y + Math.random() * 1.2, z + rr() * 0.6, 0, 0.04, 0);
        }
        // Eyes that streak behind you when you move.
        if (!me && (head.equals("head_sp") || head.equals("head_uch")) && speed > 0.18) {
            int ec = head.equals("head_sp") ? lighter(tint, 0.2f) : RED;
            for (int s = -1; s <= 1; s += 2) {
                Vec3d e = p.getEyePos().add(fwd.multiply(0.26)).add(right.multiply(0.12 * s)).add(0, -0.02, 0);
                fx(AotParticles.STREAK, ec, 0.28f, e.x, e.y, e.z, -v.x * 0.6, -v.y * 0.6, -v.z * 0.6);
            }
        }
        if (back.equals("back_sp") && t % 3 == 0) fx(AotParticles.SPARK, 0xFFFFFF, 0.35f, x + rr() * 0.6, y + 2 + Math.random() * 6, z + rr() * 0.6, 0, 0.35, 0);
        if (!me && back.equals("back_uch") && t % 4 == 0) {
            double a = Math.random() * Math.PI * 2;
            fx(AotParticles.PUFF, PURPLE, 0.6f, x + Math.cos(a) * 0.55, y + 0.6 + Math.random() * 1.1, z + Math.sin(a) * 0.55, 0, 0.03, 0);
        }
        String horse = CosmeticFx.worn(p.getUuid(), "horse");
        if (ours(horse) && p.getVehicle() instanceof AbstractHorseEntity h) {
            double hs = h.getPos().subtract(h.prevX, h.prevY, h.prevZ).horizontalLength();
            if (hs > 0.22 && h.isOnGround()) horseFx(horse, sp(horse) ? tint : 0, h.getX(), h.getY(), h.getZ(), -v.x * 0.1, -v.z * 0.1);
        }
    }

    /** Hoof-fall in either set (the showroom plays it without a horse). */
    static void horseFx(String style, int tint, double x, double y, double z, double vx, double vz) {
        for (int i = 0; i < 2; i++) {
            double px = x + rr() * 0.9, pz = z + rr() * 0.9;
            if (sp(style)) fx(AotParticles.PUFF, i == 0 ? tint : lighter(tint, 0.4f), 0.8f, px, y + 0.1, pz, vx, 0.05, vz);
            else fx(AotParticles.PUFF, i == 0 ? BLACK : PURPLE, 0.8f, px, y + 0.1, pz, vx, 0.06, vz);
        }
        if (Math.random() < 0.3) fx(AotParticles.SPARK, sp(style) ? 0xFFFFFF : LIGHT, 0.35f, x, y + 0.1, z, rr() * 0.2, 0.1, rr() * 0.2);
    }

    // ------------------------------------------------------------------ on a hit, a shot, a kill

    /** A blade slash (fx.yaw: the attacker's). */
    public static void slash(Net.SlashFx f) {
        String b = base(f.style());
        double yaw = Math.toRadians(f.yaw());
        double rx = Math.cos(yaw), rz = Math.sin(yaw), fx0 = -Math.sin(yaw), fz0 = Math.cos(yaw);
        double x = f.x(), y = f.y(), z = f.z();
        if (b.equals("slash_sp")) {
            int c = tintOf(f.style());
            fx(AotParticles.ARC, c, 3.4f, x, y, z, rx, 0.2, rz);
            fx(AotParticles.ARC, 0xFFFFFF, 2.2f, x, y, z, rx, 0.2, rz);
            fx(AotParticles.FLASH, c, 1.6f, x, y, z, 0, 0, 0);
            // The crescent flies on off the blade.
            for (int i = 0; i <= 18; i++) {
                double u = i / 18.0 * 2 - 1;
                double px = x + rx * u * 1.3, pz = z + rz * u * 1.3, py = y + 0.5 * u - (1 - u * u) * 0.35;
                fx(AotParticles.STREAK, i % 3 == 0 ? 0xFFFFFF : c, 0.7f, px, py, pz, fx0 * 1.1, 0, fz0 * 1.1);
            }
            for (int i = 0; i < 10; i++) fx(AotParticles.SPARK, lighter(c, 0.5f), 0.45f, x, y, z, rr() * 0.6 + fx0 * 0.3, rr() * 0.5, rr() * 0.6 + fz0 * 0.3);
        } else {
            fx(AotParticles.ARC, LIGHT, 2.8f, x, y, z, rx, 0.2, rz);
            fx(AotParticles.FLASH, PURPLE, 1.4f, x, y, z, 0, 0, 0);
            for (int i = 0; i < 12; i++) {
                double u = i / 12.0 * 2 - 1;
                fx(AotParticles.STREAK, i % 2 == 0 ? 0xFFFFFF : LIGHT, 0.45f, x + rx * u * 1.1, y + u * 0.4, z + rz * u * 1.1, rr() * 0.9, rr() * 0.9, rr() * 0.9);
            }
            for (int i = 0; i < 14; i++) fx(AotParticles.SPARK, LIGHT, 0.4f, x, y, z, rr() * 0.7, rr() * 0.6, rr() * 0.7);
            for (int i = 0; i < 6; i++) fx(AotParticles.PUFF, BLACK, 0.8f, x + rr() * 0.6, y + rr() * 0.6, z + rr() * 0.6, 0, 0.04, 0);
        }
    }

    /** A round's trail, start to end. */
    public static void trail(Net.Trail t) {
        var w = MinecraftClient.getInstance().world;
        if (w == null) return;
        Vec3d a = new Vec3d(t.x0(), t.y0(), t.z0()), b = new Vec3d(t.x1(), t.y1(), t.z1()), d = b.subtract(a);
        double len = d.length();
        if (len < 0.1 || len > 200) return;
        Vec3d dn = d.normalize();
        Vec3d up = Math.abs(dn.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d s1 = dn.crossProduct(up).normalize(), s2 = dn.crossProduct(s1).normalize();
        int steps = (int) Math.min(200, len * 2.5);
        boolean sp = sp(t.style());
        int c = sp ? tintOf(t.style()) : LIGHT;
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            Vec3d p = a.add(d.multiply(f));
            if (sp) {
                // A bright core, and light spiralling round it.
                w.addParticle(Fxs.dot(lighter(c, 0.5f), 0.55f), true, p.x, p.y, p.z, 0, 0, 0);
                if (i % 3 == 0) {
                    double ang = i * 0.55;
                    Vec3d o = s1.multiply(Math.cos(ang) * 0.18).add(s2.multiply(Math.sin(ang) * 0.18));
                    fx(AotParticles.SPARK, c, 0.38f, p.x + o.x, p.y + o.y, p.z + o.z, o.x * 0.2, o.y * 0.2, o.z * 0.2);
                }
            } else {
                // A bolt: jagged, white at the core, branches off it.
                double zig = Math.sin(f * 47) * 0.14;
                Vec3d q = p.add(s1.multiply(zig)).add(s2.multiply(Math.cos(f * 31) * 0.1));
                w.addParticle(Fxs.dot(i % 3 == 0 ? 0xFFFFFF : LIGHT, 0.55f), true, q.x, q.y, q.z, 0, 0, 0);
                if (i % 28 == 14) {
                    Vec3d br = s1.multiply(rr()).add(s2.multiply(rr())).normalize();
                    fx(AotParticles.STREAK, 0xEAF6FF, 0.5f, q.x, q.y, q.z, br.x * 0.7, br.y * 0.7, br.z * 0.7);
                }
                if (i % 12 == 0) fx(AotParticles.PUFF, BLACK, 0.5f, p.x, p.y, p.z, 0, 0.02, 0);
            }
        }
        fx(AotParticles.FLASH, c, 1.1f, a.x, a.y, a.z, 0, 0, 0);
        fx(AotParticles.BURST, c, 1.8f, b.x, b.y, b.z, 0, 0, 0);
        for (int i = 0; i < 8; i++) fx(AotParticles.SPARK, sp ? lighter(c, 0.4f) : LIGHT, 0.4f, b.x, b.y, b.z, rr() * 0.5, rr() * 0.5, rr() * 0.5);
    }

    /** A guard turning a blow (kind 0), or two blades meeting (kind 1). */
    public static void guard(Net.GuardFx g) {
        boolean sp = sp(g.style()), clash = g.kind() == 1;
        int c = sp ? tintOf(g.style()) : clash ? LIGHT : PURPLE;
        double x = g.x(), y = g.y(), z = g.z();
        fx(AotParticles.BURST, c, clash ? 3.2f : 2.4f, x, y, z, 0, 0, 0);
        fx(AotParticles.FLASH, clash ? 0xFFFFFF : c, clash ? 2f : 1.3f, x, y, z, 0, 0, 0);
        if (sp) {
            fx(AotParticles.RING, c, clash ? 4f : 2.6f, x, y - 0.6, z, 0, 0, 0);
            for (int i = 0; i < (clash ? 24 : 12); i++) {
                Vec3d dir = new Vec3d(rr(), rr(), rr()).normalize();
                fx(AotParticles.STREAK, i % 3 == 0 ? 0xFFFFFF : c, 0.6f, x, y, z, dir.x * 0.9, dir.y * 0.9, dir.z * 0.9);
            }
        } else if (clash) {
            for (int i = 0; i < 30; i++) fx(AotParticles.SPARK, i % 2 == 0 ? LIGHT : 0xFFFFFF, 0.45f, x, y, z, rr() * 0.9, rr() * 0.9, rr() * 0.9);
            for (int i = 0; i < 10; i++) {
                Vec3d dir = new Vec3d(rr(), rr(), rr()).normalize();
                fx(AotParticles.STREAK, 0xEAF6FF, 0.5f, x, y, z, dir.x, dir.y, dir.z);
            }
        } else {
            // The ribcage comes up: purple bone and smoke.
            for (int i = 0; i < 16; i++) fx(AotParticles.SHARD, i % 2 == 0 ? PURPLE : 0xB08AF0, 0.6f, x, y, z, rr() * 0.5, Math.random() * 0.35, rr() * 0.5);
            fx(AotParticles.ARC, PURPLE, 2.6f, x, y, z, 0, 1, 0);
            for (int i = 0; i < 6; i++) fx(AotParticles.PUFF, PURPLE, 0.9f, x + rr(), y + rr(), z + rr(), 0, 0.03, 0);
        }
    }

    /** The kill effect, at the centre of what died (h tall). */
    public static void kill(String style, double x, double y, double z, float h) {
        double bottom = y - h / 2;
        if (sp(style)) {
            int c = tintOf(style);
            // A pillar of pressure, straight up into the sky.
            for (int i = 0; i < 44; i++) {
                fx(AotParticles.STREAK, i % 4 == 0 ? 0xFFFFFF : c, 0.9f + (float) Math.random() * 0.5f, x + rr() * 0.9, bottom + i * 0.35, z + rr() * 0.9, 0, 1.6, 0);
            }
            fx(AotParticles.FLASH, 0xFFFFFF, 3.2f, x, y, z, 0, 0, 0);
            fx(AotParticles.BURST, c, 6f, x, y, z, 0, 0, 0);
            for (int i = 0; i < 3; i++) fx(AotParticles.RING, i == 1 ? 0xFFFFFF : c, 3f + i * 2.6f, x, bottom + 0.05, z, 0, 0, 0);
            for (int i = 0; i < 30; i++) fx(AotParticles.SHARD, lighter(c, 0.3f), 0.55f, x, y, z, rr() * 0.8, Math.random() * 0.5, rr() * 0.8);
            for (int i = 0; i < 20; i++) fx(AotParticles.PUFF, c, 1.2f, x + rr() * 1.4, bottom + Math.random() * h, z + rr() * 1.4, 0, 0.08, 0);
        } else {
            // Black flames swallow it.
            for (int i = 0; i < 64; i++) {
                double a = Math.random() * Math.PI * 2, r = Math.random() * 0.9;
                fx(AotParticles.PUFF, i % 5 == 0 ? PURPLE : BLACK, 1f + (float) Math.random() * 0.8f, x + Math.cos(a) * r, bottom + Math.random() * h, z + Math.sin(a) * r,
                    0, 0.05 + Math.random() * 0.06, 0);
            }
            for (int i = 0; i < 3; i++) fx(AotParticles.STREAK, 0xEAF6FF, 1.6f, x + rr() * 0.6, y + 7, z + rr() * 0.6, 0, -2.2, 0);
            fx(AotParticles.FLASH, LIGHT, 3f, x, y, z, 0, 0, 0);
            fx(AotParticles.RING, PURPLE, 4f, x, bottom + 0.05, z, 0, 0, 0);
            for (int i = 0; i < 20; i++) fx(AotParticles.SPARK, LIGHT, 0.5f, x, y, z, rr() * 0.8, Math.random() * 0.6, rr() * 0.8);
        }
    }

    /** Gas off the gear, in either set. */
    static void odm(String style, double x, double y, double z, double vx, double vy, double vz) {
        var r = new java.util.Random();
        if (sp(style)) {
            int c = tintOf(style);
            fx(AotParticles.PUFF, r.nextBoolean() ? c : lighter(c, 0.4f), 0.9f + r.nextFloat() * 0.5f, x, y, z, vx, vy, vz);
            if (r.nextInt(3) == 0) fx(AotParticles.STREAK, lighter(c, 0.5f), 0.5f, x, y, z, vx * 1.6, vy * 1.6, vz * 1.6);
        } else {
            fx(AotParticles.PUFF, r.nextInt(3) == 0 ? PURPLE : BLACK, 0.9f + r.nextFloat() * 0.5f, x, y, z, vx, vy, vz);
            if (r.nextInt(3) == 0) fx(AotParticles.SPARK, LIGHT, 0.4f, x, y, z, vx * 1.5 + rr() * 0.2, vy * 1.5 + rr() * 0.2, vz * 1.5 + rr() * 0.2);
        }
    }

    // ------------------------------------------------------------------ worn on the model

    /**
     * Glowing eyes, on the face (the matrix is the head's own, model space: the face is at -Z,
     * down is +Y, in blocks).
     */
    public static void eyes(MatrixStack ms, VertexConsumerProvider vc, float time, String head, int tint) {
        Matrix4f m = ms.peek().getPositionMatrix();
        boolean red = head.equals("head_uch");
        // Exactly over the skin's eyes (two pixels wide, one tall, a row below the middle of the
        // face), just in front of the hat layer so nothing covers them.
        float px = 1 / 16f, zf = -4.6f * px, y0 = -4 * px, y1 = -3 * px;
        int core = red ? 0xFFE81A1A : 0xFF000000 | lighter(tint, 0.35f);
        VertexConsumer glow = vc.getBuffer(RenderLayer.getLightning());
        VertexConsumer solid = vc.getBuffer(RenderLayer.getDebugQuads());
        for (int s = -1; s <= 1; s += 2) {
            float x0 = s < 0 ? -3 * px : px, x1 = x0 + 2 * px;
            quad(solid, m, x0, y0, zf, x1, y1, red ? 0xFFB01010 : 0xFF000000 | Showcase.mix(tint, 0, 0.35f));
            quad(glow, m, x0, y0, zf - 0.001f, x1, y1, core);
            // The pupil: one dark dot in the middle of each red eye.
            if (red) quad(solid, m, x0 + 0.75f * px, y0 + 0.25f * px, zf - 0.002f, x0 + 1.25f * px, y1 - 0.25f * px, 0xFF140404);
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float x0, float y0, float z, float x1, float y1, int argb) {
        vc.vertex(m, x0, y0, z).color(argb);
        vc.vertex(m, x0, y1, z).color(argb);
        vc.vertex(m, x1, y1, z).color(argb);
        vc.vertex(m, x1, y0, z).color(argb);
    }

    /**
     * On the back (the matrix between the shoulder blades, +Y up, +Z forward): a mantle of light
     * flowing up and back off the shoulders (_sp), or a spectral warrior's ribcage round the body (_uch).
     */
    public static void back(MatrixStack ms, VertexConsumerProvider vc, float time, String id, int tint, double speed) {
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer glow = vc.getBuffer(RenderLayer.getLightning());
        if (id.equals("back_sp")) {
            int ribbons = 9;
            float blow = (float) Math.min(0.5, speed * 1.4);
            for (int i = 0; i < ribbons; i++) {
                float u = i / (float) (ribbons - 1) * 2 - 1;
                float x = u * 0.32f;
                float phase = time * 3.2f + i * 0.9f;
                float len = 0.9f + 0.35f * MathHelper.sin(phase * 0.7f) + blow;
                int seg = 6;
                for (int k = 0; k < seg; k++) {
                    float a0 = k / (float) seg, a1 = (k + 1) / (float) seg;
                    float w0 = 0.07f * (1 - a0 * 0.8f), w1 = 0.07f * (1 - a1 * 0.8f);
                    float sx0 = x + MathHelper.sin(phase + a0 * 4) * 0.06f * a0, sx1 = x + MathHelper.sin(phase + a1 * 4) * 0.06f * a1;
                    // Up off the shoulders and back, licking like flame.
                    float y0 = 0.05f + a0 * len * 0.75f, y1 = 0.05f + a1 * len * 0.75f;
                    float z0 = -0.17f - a0 * len * (0.45f + blow), z1 = -0.17f - a1 * len * (0.45f + blow);
                    int c0 = (int) (0xC0 * (1 - a0)) << 24 | lighter(tint, 0.25f * (1 - a0)), c1 = (int) (0xC0 * (1 - a1)) << 24 | tint;
                    glow.vertex(m, sx0 - w0, y0, z0).color(c0);
                    glow.vertex(m, sx0 + w0, y0, z0).color(c0);
                    glow.vertex(m, sx1 + w1, y1, z1).color(c1);
                    glow.vertex(m, sx1 - w1, y1, z1).color(c1);
                    glow.vertex(m, sx1 - w1, y1, z1).color(c1);
                    glow.vertex(m, sx1 + w1, y1, z1).color(c1);
                    glow.vertex(m, sx0 + w0, y0, z0).color(c0);
                    glow.vertex(m, sx0 - w0, y0, z0).color(c0);
                }
            }
        } else {
            // Ribs curving from the spine (behind) round to the front, purple and glowing; a spine down the back.
            float breathe = 1 + 0.03f * MathHelper.sin(time * 2.2f);
            int col = 0xB07A3AD0, edge = 0x60B08AF0;
            for (int r = 0; r < 5; r++) {
                float y = 0.1f - r * 0.17f, rad = (0.52f - Math.abs(r - 1.5f) * 0.04f) * breathe;
                int seg = 10;
                for (int s = -1; s <= 1; s += 2) {
                    for (int k = 0; k < seg; k++) {
                        double a0 = Math.PI * (k / (double) seg) * 0.85, a1 = Math.PI * ((k + 1) / (double) seg) * 0.85;
                        // From behind (angle 0 = -Z) round the side toward the front.
                        float x0 = (float) Math.sin(a0) * rad * s, z0 = -(float) Math.cos(a0) * rad * 0.8f - 0.05f;
                        float x1 = (float) Math.sin(a1) * rad * s, z1 = -(float) Math.cos(a1) * rad * 0.8f - 0.05f;
                        float dy = -0.015f * k;
                        glow.vertex(m, x0, y + dy + 0.03f, z0).color(col);
                        glow.vertex(m, x1, y + dy - 0.015f + 0.03f, z1).color(edge);
                        glow.vertex(m, x1, y + dy - 0.015f - 0.03f, z1).color(edge);
                        glow.vertex(m, x0, y + dy - 0.03f, z0).color(col);
                        glow.vertex(m, x0, y + dy - 0.03f, z0).color(col);
                        glow.vertex(m, x1, y + dy - 0.015f - 0.03f, z1).color(edge);
                        glow.vertex(m, x1, y + dy - 0.015f + 0.03f, z1).color(edge);
                        glow.vertex(m, x0, y + dy + 0.03f, z0).color(col);
                    }
                }
            }
            float sz = -0.42f * breathe;
            glow.vertex(m, -0.04f, 0.25f, sz).color(col);
            glow.vertex(m, 0.04f, 0.25f, sz).color(col);
            glow.vertex(m, 0.04f, -0.8f, sz).color(edge);
            glow.vertex(m, -0.04f, -0.8f, sz).color(edge);
            glow.vertex(m, -0.04f, -0.8f, sz).color(edge);
            glow.vertex(m, 0.04f, -0.8f, sz).color(edge);
            glow.vertex(m, 0.04f, 0.25f, sz).color(col);
            glow.vertex(m, -0.04f, 0.25f, sz).color(col);
        }
    }
}
