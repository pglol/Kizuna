package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.Map;

/**
 * Body language, worked out from how a body moves, so everyone sees it on everyone (people too):
 * <ul>
 *   <li>stopping hard from a sprint: a skid, heels dug in and arms out (faster still: a low slide,
 *   a hand dragging the ground), dust kicked up; after a long run, hands on the knees for a breath;</li>
 *   <li>reversing at a run: a planted pivot, low and leaning into the turn;</li>
 *   <li>a long drop landed on the move: a forward roll; landed standing: a three-point landing;</li>
 *   <li>standing about: idles. Stretching up and to the sides, neck and shoulder rolls, an arm
 *   pulled across, checking the blades or the gear, looking round, cracking the knuckles, hands
 *   on the hips, a foot tapping, a yawn, arms folded, a hamstring stretch, dusting off, a squat;
 *   left long enough, sitting down to rest.</li>
 * </ul>
 * How lively the idles are is each player's choice (Lively, Calm, Soldier, Off).
 */
public final class Gestures {
    private Gestures() {}

    static final int SKID = 1, SLIDE = 2, PIVOT = 3, ROLL = 4, HERO = 5, BREATH = 6, REST = 7, IDLE = 20;
    static final int STRETCH = 0, SIDE = 1, NECK = 2, SHOULDERS = 3, ACROSS = 4, BLADES = 5, LOOK = 6, KNUCKLES = 7, HIPS = 8, TAP = 9,
        YAWN = 10, CROSSED = 11, HAMSTRING = 12, DUST = 13, SQUAT = 14, GEAR = 15, EASE = 16;
    static final long[] IDLE_MS = {3200, 3600, 2600, 2400, 3400, 2800, 3400, 2200, 4200, 2600, 2800, 4800, 3400, 2000, 3600, 2800, 6000};
    private static final int[] LIVELY = {STRETCH, SIDE, NECK, SHOULDERS, ACROSS, BLADES, LOOK, KNUCKLES, HIPS, TAP, YAWN, CROSSED, HAMSTRING, DUST, SQUAT, GEAR};
    private static final int[] CALM = {STRETCH, NECK, LOOK, TAP, CROSSED, HIPS, YAWN, LOOK, CROSSED};
    private static final int[] SOLDIER = {EASE, LOOK, EASE, GEAR, EASE, BLADES, EASE, SHOULDERS, EASE, NECK};

    public static final String[] IDLE_STYLES = {"", "calm", "soldier", "off"};
    public static final String[] IDLE_NAMES = {"Lively", "Calm", "Soldier", "Off"};

    private static final class G {
        long at, fastAt, sprintFrom, runFrom, stillFrom, airFrom, lastDust, nextIdle;
        double hx, hz;
        float peak, minVy;
        boolean ground = true;
        int act, side, last = -1;
        long actAt, actLen;
    }

    private static final Map<Integer, G> gs = new HashMap<>();
    private static final java.util.Random rnd = new java.util.Random();

    public static String idleName(String s) {
        for (int i = 0; i < IDLE_STYLES.length; i++) if (IDLE_STYLES[i].equals(s)) return IDLE_NAMES[i];
        return IDLE_NAMES[0];
    }

    public static String nextIdle(String s) {
        for (int i = 0; i < IDLE_STYLES.length; i++) if (IDLE_STYLES[i].equals(s)) return IDLE_STYLES[(i + 1) % IDLE_STYLES.length];
        return IDLE_STYLES[1];
    }

    private static boolean blocked(LivingEntity e) {
        if (e.deathTime > 0 || e.isSleeping() || e.hasVehicle() || e.isSwimming() || e.isFallFlying() || e.isClimbing() || e.isTouchingWater()) return true;
        int id = e.getId();
        if (FinishScene.in(id) || AdrenalineClient.pinned(id) || AdrenalineClient.faceplant(id) > 0 || OdmMoves.anim(id) != null || Emotes.active(id)) return true;
        if (e instanceof PlayerEntity p && p.getAbilities().flying) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        // Your own arms in first person are left alone.
        return e == mc.player && !mc.gameRenderer.getCamera().isThirdPerson();
    }

    private static void start(G g, int act, long len, long now) {
        g.act = act;
        g.actAt = now;
        g.actLen = len;
    }

    /** Ends the current one gently (moving off, a swing, a hit). */
    private static void cut(G g, long now) {
        if (g.act == 0) return;
        long el = now - g.actAt;
        if (g.actLen - el > 220) g.actLen = el + 220;
    }

    private static String idleStyle(LivingEntity e) {
        if (e instanceof PlayerEntity p) return Poses.idleOf(p.getUuid());
        return "calm";
    }

    private static G update(LivingEntity e) {
        long now = Util.getMeasuringTimeMs();
        if (gs.size() > 320) gs.clear();
        G g = gs.computeIfAbsent(e.getId(), k -> new G());
        if (now - g.at < 12) return g;
        float dts = g.at == 0 ? 0.016f : Math.min(0.1f, (now - g.at) / 1000f);
        g.at = now;
        double dx = e.getX() - e.prevX, dz = e.getZ() - e.prevZ, dy = e.getY() - e.prevY;
        float sp = (float) Math.sqrt(dx * dx + dz * dz);
        boolean ground = e.isOnGround();
        if (blocked(e)) {
            g.act = 0;
            g.stillFrom = now;
            g.nextIdle = 0;
            g.ground = ground;
            g.fastAt = 0;
            return g;
        }
        if (g.act != 0 && now - g.actAt > g.actLen) {
            int was = g.act;
            g.act = 0;
            // A long run, then the stop: a breather.
            if ((was == SKID || was == SLIDE) && g.sprintFrom > 0 && g.fastAt - g.sprintFrom > 5000 && sp < 0.02f && ground) start(g, BREATH, 1900, now);
        }
        // ---- on the move
        g.peak = Math.max(sp, g.peak - dts * 0.6f);
        // Only a real sprint counts: flat out (a sprint, not a jog), for most of a second.
        boolean sprinting = !(e instanceof PlayerEntity pl) || pl.isSprinting() || sp > 0.4f;
        if (sp > 0.265f && sprinting) {
            if (now - g.fastAt > 400) g.sprintFrom = now;
            if (now - g.fastAt > 150) g.runFrom = now;
            g.fastAt = now;
        }
        boolean loco = g.act == SKID || g.act == SLIDE || g.act == ROLL || g.act == HERO || g.act == PIVOT;
        if (ground && g.ground && sp < 0.05f && g.fastAt > 0 && now - g.fastAt < 180 && g.fastAt - g.runFrom > 700 && !loco) {
            boolean slide = g.peak > 0.45f;
            start(g, slide ? SLIDE : SKID, slide ? 720 : 520, now);
            g.fastAt = 0;
            g.side = rnd.nextBoolean() ? 1 : -1;
        }
        if (sp > 0.1f) {
            double ux = dx / sp, uz = dz / sp;
            if (ground && sp > 0.22f && g.fastAt - g.runFrom > 500 && (g.hx != 0 || g.hz != 0) && ux * g.hx + uz * g.hz < -0.25 && !loco) {
                start(g, PIVOT, 380, now);
                g.side = ux * g.hz - uz * g.hx > 0 ? 1 : -1;
                g.hx = ux;
                g.hz = uz;
            } else {
                g.hx += (ux - g.hx) * 0.25;
                g.hz += (uz - g.hz) * 0.25;
                double l = Math.sqrt(g.hx * g.hx + g.hz * g.hz);
                if (l > 1e-4) {
                    g.hx /= l;
                    g.hz /= l;
                }
            }
        }
        // ---- in the air, and down again
        if (!ground) {
            if (g.ground) {
                g.airFrom = now;
                g.minVy = 0;
            }
            g.minVy = Math.min(g.minVy, (float) dy);
        } else if (!g.ground && g.airFrom > 0) {
            long air = now - g.airFrom;
            if (air > 700 && g.minVy < -0.55f) {
                if (sp > 0.17f) start(g, ROLL, 560, now);
                else if (g.minVy < -0.85f) start(g, HERO, 950, now);
                if (g.act == ROLL || g.act == HERO) burst(e, g.act == HERO ? 18 : 8);
            }
            g.airFrom = 0;
        }
        g.ground = ground;
        if ((g.act == SKID || g.act == SLIDE) && now - g.lastDust > 45) {
            g.lastDust = now;
            dust(e, g, sp);
        }
        // ---- standing about
        boolean still = sp < 0.004f && Math.abs(dy) < 0.01 && ground;
        boolean fidget = e.handSwinging || e.hurtTime > 0 || e.isUsingItem() || e.isSneaking();
        if (!still || fidget) {
            g.stillFrom = now;
            g.nextIdle = 0;
            if (g.act >= IDLE || g.act == REST || g.act == BREATH && (!still || fidget)) cut(g, now);
            return g;
        }
        String style = idleStyle(e);
        if (style.equals("off")) return g;
        boolean player = e instanceof PlayerEntity;
        if (g.act == 0 && player && !style.equals("soldier") && now - g.stillFrom > 45000) {
            start(g, REST, Long.MAX_VALUE / 4, now);
            return g;
        }
        if (g.act != 0) return g;
        if (g.nextIdle == 0) {
            long first = style.equals("soldier") ? 1800 : style.equals("calm") ? 9000 : 5000;
            g.nextIdle = now + first + rnd.nextInt(player ? 3000 : 9000);
            return g;
        }
        if (now < g.nextIdle) return g;
        int[] set = style.equals("soldier") ? SOLDIER : style.equals("calm") || !player ? CALM : LIVELY;
        int pick = set[rnd.nextInt(set.length)];
        for (int tries = 0; tries < 6 && (pick == g.last && pick != EASE || pick == BLADES && e.getMainHandStack().isEmpty()); tries++) pick = set[rnd.nextInt(set.length)];
        if (pick == BLADES && e.getMainHandStack().isEmpty()) pick = LOOK;
        g.last = pick;
        g.side = rnd.nextBoolean() ? 1 : -1;
        start(g, IDLE + pick, IDLE_MS[pick], now);
        long gap = style.equals("soldier") ? 200 + rnd.nextInt(800) : style.equals("calm") ? 9000 + rnd.nextInt(7000) : 3500 + rnd.nextInt(5500);
        if (!player) gap += 6000 + rnd.nextInt(10000);
        g.nextIdle = now + IDLE_MS[pick] + gap;
        return g;
    }

    private static void dust(LivingEntity e, G g, float sp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || e.squaredDistanceTo(mc.gameRenderer.getCamera().getPos()) > 40 * 40) return;
        var st = mc.world.getBlockState(BlockPos.ofFloored(e.getX(), e.getY() - 0.2, e.getZ()));
        if (st.isAir()) return;
        for (int i = 0; i < (g.act == SLIDE ? 3 : 2); i++) {
            mc.world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), e.getX() + g.hx * 0.4 + (Math.random() - 0.5) * 0.4, e.getY() + 0.05,
                e.getZ() + g.hz * 0.4 + (Math.random() - 0.5) * 0.4, g.hx * 0.25, 0.08 + Math.random() * 0.08, g.hz * 0.25);
        }
    }

    private static void burst(LivingEntity e, int n) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || e.squaredDistanceTo(mc.gameRenderer.getCamera().getPos()) > 48 * 48) return;
        var st = mc.world.getBlockState(BlockPos.ofFloored(e.getX(), e.getY() - 0.2, e.getZ()));
        if (!st.isAir()) {
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                mc.world.addParticle(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), e.getX() + Math.cos(a) * 0.5, e.getY() + 0.05, e.getZ() + Math.sin(a) * 0.5,
                    Math.cos(a) * 0.2, 0.1, Math.sin(a) * 0.2);
            }
        }
        if (n > 10) FxParticle.spawn(AotParticles.RING, 0xD8C8A8, 1.8f, e.getX(), e.getY() + 0.05, e.getZ(), 0, 0, 0);
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** How far in (0..1): eased in at the start, out at the end. */
    private static float weight(G g, long now, long in, long out) {
        long el = now - g.actAt;
        return ease(Math.min(Math.min(1, el / (float) in), (g.actLen - el) / (float) out));
    }

    private static float prog(G g, long now) {
        return MathHelper.clamp((now - g.actAt) / (float) g.actLen, 0, 1);
    }

    private static void about(MatrixStack ms, float y, RotationAxis axis, float deg) {
        if (Math.abs(deg) < 0.01f) return;
        ms.translate(0, y, 0);
        ms.multiply(axis.rotationDegrees(deg));
        ms.translate(0, -y, 0);
    }

    /** The whole body. True if a move on the ground took it over (no running lean on top). */
    public static boolean transforms(LivingEntity e, MatrixStack ms) {
        G g = update(e);
        if (g.act == 0) return false;
        long now = Util.getMeasuringTimeMs();
        float h = e.getHeight() * 0.5f, t = prog(g, now);
        switch (g.act) {
            case SKID -> {
                float k = weight(g, now, 60, 260);
                ms.translate(0, -0.12f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_X, 16 * k);
                return true;
            }
            case SLIDE -> {
                float k = weight(g, now, 70, 320);
                ms.translate(0, -0.4f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_X, 24 * k);
                about(ms, h, RotationAxis.POSITIVE_Z, 7 * k * g.side);
                return true;
            }
            case PIVOT -> {
                float k = weight(g, now, 60, 200);
                ms.translate(0, -0.18f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_Z, 18 * k * g.side);
                about(ms, h, RotationAxis.POSITIVE_X, -10 * k);
                return true;
            }
            case ROLL -> {
                float drop = 0.45f * MathHelper.sin(t * MathHelper.PI);
                ms.translate(0, -drop, 0);
                about(ms, h * 0.8f, RotationAxis.POSITIVE_X, -360 * ease(t));
                return true;
            }
            case HERO -> {
                float k = weight(g, now, 70, 380);
                ms.translate(0, -0.58f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_X, -18 * k);
                return true;
            }
            case BREATH -> {
                float k = weight(g, now, 300, 400);
                ms.translate(0, -0.1f * k + MathHelper.sin(now / 190f) * 0.012f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_X, -32 * k);
                return true;
            }
            case REST -> {
                float k = ease(Math.min(1, (now - g.actAt) / 700f));
                if (g.actLen < Long.MAX_VALUE / 8) k = Math.min(k, weight(g, now, 700, 220));
                ms.translate(0, -0.62f * k, 0);
                about(ms, h, RotationAxis.POSITIVE_X, 9 * k);
            }
            default -> {
                int idle = g.act - IDLE;
                float k = weight(g, now, 350, 450);
                switch (idle) {
                    case STRETCH -> {
                        float up = k * ease(Math.min(1, (t - 0.15f) / 0.2f)) * (1 - ease((t - 0.75f) / 0.2f));
                        ms.translate(0, 0.05f * up, 0);
                        about(ms, h, RotationAxis.POSITIVE_X, 7 * up);
                    }
                    case SIDE -> about(ms, h, RotationAxis.POSITIVE_Z, 13 * k * (t < 0.5f ? 1 : -1) * Math.min(1, Math.abs(t - 0.5f) * 8));
                    case HAMSTRING -> about(ms, h * 0.95f, RotationAxis.POSITIVE_X, -42 * k);
                    case SQUAT -> ms.translate(0, -0.5f * k, 0);
                    case YAWN -> about(ms, h, RotationAxis.POSITIVE_X, 5 * k);
                    case HIPS -> about(ms, 0, RotationAxis.POSITIVE_Z, 3 * k * g.side);
                    default -> { }
                }
            }
        }
        return false;
    }

    private static void to(ModelPart p, float pitch, float yaw, float roll, float k) {
        p.pitch = MathHelper.lerp(k, p.pitch, pitch);
        p.yaw = MathHelper.lerp(k, p.yaw, yaw);
        p.roll = MathHelper.lerp(k, p.roll, roll);
    }

    /** The limbs, on top of everything else. */
    public static void pose(BipedEntityModel<?> m, LivingEntity e, float age) {
        G g = update(e);
        if (g.act == 0) return;
        long now = Util.getMeasuringTimeMs();
        float t = prog(g, now), s = g.side;
        ModelPart ra = m.rightArm, la = m.leftArm, rl = m.rightLeg, ll = m.leftLeg, hd = m.head;
        switch (g.act) {
            case SKID -> {
                float k = weight(g, now, 60, 260);
                to(rl, -0.75f, 0, 0.05f, k);
                to(ll, 0.45f, 0, -0.08f, k);
                to(ra, -0.95f, -0.2f, 0.55f, k);
                to(la, -0.95f, 0.2f, -0.55f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.25f);
            }
            case SLIDE -> {
                float k = weight(g, now, 70, 320);
                to(rl, -1.3f, 0, 0.06f, k);
                to(ll, 0.95f, 0, -0.1f, k);
                // One hand trailing on the ground, the other out ahead.
                if (s > 0) {
                    to(ra, 0.25f, 0, 1.0f, k);
                    to(la, -1.3f, 0.2f, -0.3f, k);
                } else {
                    to(la, 0.25f, 0, -1.0f, k);
                    to(ra, -1.3f, -0.2f, 0.3f, k);
                }
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.35f);
            }
            case PIVOT -> {
                float k = weight(g, now, 60, 200);
                to(rl, -0.65f, 0, 0.1f, k);
                to(ll, 0.4f, 0, -0.1f, k);
                to(ra, -0.4f, 0, s > 0 ? 1.1f : 0.3f, k);
                to(la, -0.4f, 0, s > 0 ? -0.3f : -1.1f, k);
            }
            case ROLL -> {
                float k = MathHelper.sin(t * MathHelper.PI);
                k = Math.min(1, k * 1.6f);
                to(rl, -1.7f, 0, 0.05f, k);
                to(ll, -1.6f, 0, -0.05f, k);
                to(ra, -2.1f, 0.4f, 0, k);
                to(la, -2.1f, -0.4f, 0, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.6f);
            }
            case HERO -> {
                float k = weight(g, now, 70, 380);
                to(rl, -1.55f, 0, 0.05f, k);
                to(ll, 0.35f, 0, -0.05f, k);
                to(ra, -0.55f, 0, 0.15f, k);
                to(la, 0.7f, 0, -1.0f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, -0.35f);
            }
            case BREATH -> {
                float k = weight(g, now, 300, 400), b = MathHelper.sin(now / 190f) * 0.04f;
                to(ra, -0.75f + b, 0.15f, 0.05f, k);
                to(la, -0.75f + b, -0.15f, -0.05f, k);
                to(rl, -0.2f, 0, 0.08f, k);
                to(ll, -0.2f, 0, -0.08f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, -0.25f);
            }
            case REST -> {
                float k = ease(Math.min(1, (now - g.actAt) / 700f));
                if (g.actLen < Long.MAX_VALUE / 8) k = Math.min(k, weight(g, now, 700, 220));
                to(rl, -1.45f, 0.22f, 0, k);
                to(ll, -1.45f, -0.22f, 0, k);
                to(ra, 0.55f, 0, 0.3f, k);
                to(la, 0.55f, 0, -0.3f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, -0.1f + MathHelper.sin(now / 2200f) * 0.08f);
                hd.yaw = MathHelper.lerp(k, hd.yaw, MathHelper.sin(now / 3100f) * 0.4f);
            }
            default -> idle(m, g.act - IDLE, weight(g, now, 350, 450), t, s, now);
        }
    }

    private static void idle(BipedEntityModel<?> m, int idle, float k, float t, float s, long now) {
        ModelPart ra = m.rightArm, la = m.leftArm, rl = m.rightLeg, ll = m.leftLeg, hd = m.head;
        float sec = now / 1000f;
        switch (idle) {
            case STRETCH -> {
                float shake = t > 0.35f && t < 0.7f ? MathHelper.sin(sec * 30) * 0.03f : 0;
                to(ra, -3.0f + shake, 0, -0.22f, k);
                to(la, -3.0f - shake, 0, 0.22f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, -0.4f);
            }
            case SIDE -> {
                float w = k * Math.min(1, Math.abs(t - 0.5f) * 8);
                if (t < 0.5f) {
                    to(ra, -2.9f, 0, -0.55f, w);
                    to(la, -0.3f, -0.3f, -0.6f, w);
                } else {
                    to(la, -2.9f, 0, 0.55f, w);
                    to(ra, -0.3f, 0.3f, 0.6f, w);
                }
            }
            case NECK -> {
                float ph = t * MathHelper.TAU * 1.25f;
                hd.pitch = MathHelper.lerp(k, hd.pitch, MathHelper.sin(ph) * 0.35f);
                hd.yaw = MathHelper.lerp(k, hd.yaw, MathHelper.cos(ph) * 0.45f);
                hd.roll = MathHelper.lerp(k, hd.roll, MathHelper.cos(ph) * 0.15f);
            }
            case SHOULDERS -> {
                float ph = t * MathHelper.TAU * 2;
                ra.pitch += MathHelper.sin(ph) * 0.25f * k;
                la.pitch += MathHelper.sin(ph + 0.6f) * 0.25f * k;
                ra.roll += (0.12f + MathHelper.cos(ph) * 0.12f) * k;
                la.roll -= (0.12f + MathHelper.cos(ph + 0.6f) * 0.12f) * k;
            }
            case ACROSS -> {
                if (t < 0.5f) {
                    to(ra, -1.5f, 1.15f, 0, k);
                    to(la, -1.3f, -0.55f, 0, k);
                    hd.yaw = MathHelper.lerp(k, hd.yaw, -0.35f);
                } else {
                    to(la, -1.5f, -1.15f, 0, k);
                    to(ra, -1.3f, 0.55f, 0, k);
                    hd.yaw = MathHelper.lerp(k, hd.yaw, 0.35f);
                }
            }
            case BLADES -> {
                float turn = MathHelper.sin(sec * 3.2f) * 0.35f;
                to(ra, -1.1f, 0.35f + turn, 0, k);
                to(la, -1.0f, -0.35f + turn * 0.6f, 0, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.55f);
            }
            case LOOK -> {
                float y = t < 0.33f ? ease(t / 0.33f) * 0.9f : t < 0.7f ? MathHelper.lerp(ease((t - 0.33f) / 0.37f), 0.9f, -0.9f) : MathHelper.lerp(ease((t - 0.7f) / 0.3f), -0.9f, 0);
                hd.yaw = MathHelper.lerp(k, hd.yaw, y * s);
                hd.pitch = MathHelper.lerp(k, hd.pitch, -0.08f);
                m.body.yaw = MathHelper.lerp(k, m.body.yaw, y * s * 0.15f);
            }
            case KNUCKLES -> {
                float push = t > 0.45f && t < 0.7f ? ease((t - 0.45f) / 0.1f) * (1 - ease((t - 0.6f) / 0.1f)) : 0;
                to(ra, -1.35f - push * 0.2f, 0.5f - push * 0.25f, 0, k);
                to(la, -1.35f - push * 0.2f, -0.5f + push * 0.25f, 0, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.3f);
            }
            case HIPS -> {
                to(ra, -0.35f, 0.45f, 0.55f, k);
                to(la, -0.35f, -0.45f, -0.55f, k);
                to(s > 0 ? rl : ll, -0.18f, 0, 0.12f * s, k);
            }
            case TAP -> {
                float tap = Math.max(0, MathHelper.sin(sec * 11)) * 0.18f;
                to(rl, -0.15f - tap, 0, 0.05f, k);
                to(la, -0.35f, -0.45f, -0.55f, k);
                hd.yaw = MathHelper.lerp(k, hd.yaw, 0.25f);
            }
            case YAWN -> {
                float y = ease(Math.min(1, t / 0.3f)) * (1 - ease((t - 0.7f) / 0.25f));
                to(ra, -1.95f, 0.75f, 0, k * y);
                to(la, -0.6f, 0, -0.55f, k * y);
                hd.pitch = MathHelper.lerp(k * y, hd.pitch, -0.45f);
            }
            case CROSSED -> {
                to(ra, -0.85f, 0.78f, 0, k);
                to(la, -0.95f, -0.72f, 0, k);
                hd.roll = MathHelper.lerp(k, hd.roll, 0.1f * s);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.1f);
                to(s > 0 ? rl : ll, -0.12f, 0, 0.1f * s, k);
            }
            case HAMSTRING -> {
                to(ra, -0.55f, 0.1f, 0, k);
                to(la, -0.55f, -0.1f, 0, k);
                to(rl, -0.4f, 0, 0.03f, k);
                to(ll, 0.12f, 0, -0.03f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.2f);
            }
            case DUST -> {
                float br = MathHelper.sin(sec * 18) * 0.25f;
                if (s > 0) to(ra, -1.75f, 1.0f + br, 0, k);
                else to(la, -1.75f, -1.0f + br, 0, k);
                hd.yaw = MathHelper.lerp(k, hd.yaw, -0.5f * s);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.35f);
            }
            case SQUAT -> {
                to(rl, -1.4f, 0.15f, 0, k);
                to(ll, -1.4f, -0.15f, 0, k);
                to(ra, -1.35f, 0.2f, 0, k);
                to(la, -1.35f, -0.2f, 0, k);
            }
            case GEAR -> {
                float tug = MathHelper.sin(sec * 7) * 0.06f;
                to(ra, -0.3f + tug, 0.35f, 0.18f, k);
                to(la, -0.3f - tug, -0.35f, -0.18f, k);
                hd.pitch = MathHelper.lerp(k, hd.pitch, 0.6f);
            }
            case EASE -> {
                // At ease: hands clasped behind the back, feet apart.
                to(ra, 0.42f, -0.35f, -0.12f, k);
                to(la, 0.42f, 0.35f, 0.12f, k);
                to(rl, 0, 0, 0.09f, k);
                to(ll, 0, 0, -0.09f, k);
            }
            default -> { }
        }
    }
}
