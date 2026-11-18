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
        if (!me && body.equals("body_sp")) {
            // Tongues of light burning up off the whole body.
            for (int i = 0; i < 3; i++) {
                double a = Math.random() * Math.PI * 2, r = 0.28 + Math.random() * 0.32;
                fx(AotParticles.PUFF, i == 0 ? lighter(tint, 0.35f) : tint, 0.45f + (float) Math.random() * 0.45f, x + Math.cos(a) * r, y + Math.random() * 1.75,
                    z + Math.sin(a) * r, Math.cos(a) * 0.012, 0.05 + Math.random() * 0.07, Math.sin(a) * 0.012);
            }
            if (t % 2 == 0) {
                double a = Math.random() * Math.PI * 2;
                fx(AotParticles.STREAK, lighter(tint, 0.55f), 0.5f, x + Math.cos(a) * 0.4, y + 0.2 + Math.random() * 1.2, z + Math.sin(a) * 0.4, 0, 0.32, 0);
            }
            if (t % 5 == 0) fx(AotParticles.SPARK, 0xFFFFFF, 0.35f, x + rr() * 0.8, y + Math.random() * 1.8, z + rr() * 0.8, 0, 0.08, 0);
            // The weight of it: a pulse of pressure along the ground.
            if (t % 28 == Math.floorMod(p.getId(), 28)) fx(AotParticles.RING, tint, 2.4f, x, y + 0.05, z, 0, 0, 0);
            if (speed > 0.25 && p.isOnGround() && t % 2 == 0) fx(AotParticles.PUFF, tint, 1f, x - v.x * 2, y + 0.15, z - v.z * 2, 0, 0.02, 0);
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
        if (!me && back.equals("back_sp") && t % 2 == 0) {
            Vec3d sh = p.getPos().add(fwd.multiply(-0.25)).add(right.multiply(rr() * 0.6)).add(0, 1.3 + Math.random() * 0.3, 0);
            fx(AotParticles.SPARK, lighter(tint, 0.4f), 0.4f, sh.x, sh.y, sh.z, -fwd.x * 0.04, 0.12, -fwd.z * 0.04);
        }
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
        int core = red ? 0xFFE01A1A : 0xFF000000 | lighter(tint, 0.45f);
        int glowA = red ? 0x90FF2A1A : 0x90000000 | tint;
        float pulse = 0.85f + 0.15f * MathHelper.sin(time * 4);
        VertexConsumer glow = vc.getBuffer(RenderLayer.getLightning());
        float zf = -0.2535f, y0 = -0.27f, y1 = -0.22f;
        for (int s = -1; s <= 1; s += 2) {
            float cx = s * 0.125f;
            // A soft halo round each eye, then the eye itself.
            float hw = 0.1f * pulse, hh = 0.06f * pulse;
            quad(glow, m, cx - hw, (y0 + y1) / 2 - hh, zf - 0.002f, cx + hw, (y0 + y1) / 2 + hh, 0x50000000 | (glowA & 0xFFFFFF));
            quad(glow, m, cx - 0.06f, y0, zf - 0.003f, cx + 0.06f, y1, core);
        }
        if (red) {
            // The pupils: dark, three marks round them.
            VertexConsumer solid = vc.getBuffer(RenderLayer.getDebugQuads());
            for (int s = -1; s <= 1; s += 2) {
                float cx = s * 0.125f;
                quad(solid, m, cx - 0.014f, -0.252f, zf - 0.004f, cx + 0.014f, -0.238f, 0xFF140404);
                for (int k = 0; k < 3; k++) {
                    double a = time * 1.5 + k * Math.PI * 2 / 3;
                    float tx = cx + (float) Math.cos(a) * 0.034f, ty = -0.245f + (float) Math.sin(a) * 0.017f;
                    quad(solid, m, tx - 0.007f, ty - 0.006f, zf - 0.004f, tx + 0.007f, ty + 0.006f, 0xFF140404);
                }
            }
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
