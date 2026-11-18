package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;

/**
 * A finisher as a scene everyone nearby sees, played through start to finish:
 * <ol>
 *   <li>the dash in, blades swept back low (0-6);</li>
 *   <li>a leap over them, spinning with the blades out wide (6-16);</li>
 *   <li>the wind-up at the top and the drop, an overhead cut with both blades (16-20);</li>
 *   <li>sliding on through and past them, down on one knee behind them, back to back (20-26);</li>
 *   <li>the held breath: still, blades out, while they stand a beat too long (26-38);</li>
 *   <li>the cut lands late: they fall (38), and the one who did it rises and flicks the blades clean (44-56).</li>
 * </ol>
 * The body's path is drawn here, smooth, from where the scene began (the server just puts the
 * one finishing where they end up). The two in it get a camera of their own, cut like a fight
 * in an anime, easing back into the ordinary view at the end.
 */
public final class FinishScene {
    private FinishScene() {}

    public static final float DASH = 6, LEAP = 16, CUT = 20, SLIDE = 26, KILL = 38, RISE = 44, END = 56;

    private record Scene(int by, int on, long at, Vec3d from, Vec3d foe, Vec3d dir, float yaw) {
        Vec3d side() {
            return new Vec3d(-dir.z, 0, dir.x);
        }
    }

    /** Each one in a scene, by entity id. */
    private static final Map<Integer, Scene> scenes = new HashMap<>();

    public static void start(int by, int on) {
        var mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Entity a = mc.world.getEntityById(by), b = mc.world.getEntityById(on);
        if (a == null || b == null) return;
        Vec3d from = a.getPos(), foe = b.getPos();
        Vec3d dir = foe.subtract(from).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0, a.getYaw()).multiply(1, 0, 1).normalize() : dir.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        Scene s = new Scene(by, on, Util.getMeasuringTimeMs(), from, foe, dir, yaw);
        scenes.put(by, s);
        scenes.put(on, s);
    }

    public static void stop(int by) {
        Scene s = scenes.get(by);
        if (s == null) return;
        scenes.remove(s.by());
        scenes.remove(s.on());
    }

    private static Scene of(int id) {
        Scene s = scenes.get(id);
        if (s == null) return null;
        if (t(s) > END) {
            scenes.remove(s.by());
            scenes.remove(s.on());
            return null;
        }
        return s;
    }

    private static float t(Scene s) {
        return (Util.getMeasuringTimeMs() - s.at()) / 50f;
    }

    public static boolean in(int id) {
        return of(id) != null;
    }

    /** You're in one (either side): the controls are held and the camera is the scene's. */
    public static boolean mine() {
        var mc = MinecraftClient.getInstance();
        return mc.player != null && in(mc.player.getId());
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    private static float seg(float t, float a, float b) {
        return ease((t - a) / (b - a));
    }

    // ------------------------------------------------------------------ the path

    /** Where the one finishing is at time t (feet). */
    private static Vec3d path(Scene s, float t) {
        Vec3d d = s.dir(), side = s.side(), v = s.foe();
        Vec3d s1 = v.subtract(d.multiply(2.3)), s2 = v.subtract(d.multiply(0.5)).add(0, 2.7, 0);
        Vec3d s3 = v.subtract(d.multiply(0.9)), end = v.add(d.multiply(1.9));
        if (t < DASH) {
            // Fast off the mark, easing into the leap.
            float k = MathHelper.clamp(t / DASH, 0, 1);
            k = 1 - (1 - k) * (1 - k);
            return s.from().lerp(s1, k);
        }
        if (t < LEAP) {
            float k = (t - DASH) / (LEAP - DASH);
            Vec3d flat = s1.lerp(s2.multiply(1, 0, 1).add(0, s1.y, 0), ease(k));
            // An arc: up fast, hanging at the top.
            double h = (s2.y - s1.y) * Math.sin(Math.min(1, k * 1.15) * Math.PI / 2);
            return new Vec3d(flat.x, s1.y + h, flat.z);
        }
        if (t < CUT) {
            float k = (t - LEAP) / (CUT - LEAP);
            return s2.lerp(s3, k * k);
        }
        if (t < SLIDE) {
            // Through and past, swinging wide round them.
            float k = seg(t, CUT, SLIDE);
            Vec3d p = s3.lerp(end, k);
            return p.add(side.multiply(Math.sin(k * Math.PI) * 0.8));
        }
        return end;
    }

    /** Which way the one finishing faces: along the line, with a full turn in the leap. */
    private static float facing(Scene s, float t) {
        return s.yaw() + 360 * seg(t, DASH + 1, LEAP - 1);
    }

    // ------------------------------------------------------------------ poses

    // rArm pitch/yaw/roll, lArm pitch/yaw/roll, rLeg pitch, lLeg pitch, head pitch, lean (deg), drop (blocks)
    private static final float[][] BY = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {2, 0.9f, 0, 0.5f, 0.9f, 0, -0.5f, -1.0f, 0.7f, -0.3f, 35, 0.1f},
        {6, 0.9f, 0, 0.6f, 0.9f, 0, -0.6f, -1.1f, 0.8f, -0.3f, 38, 0.15f},
        {8, 0.6f, 0, 1.2f, 0.6f, 0, -1.2f, -1.3f, -1.1f, -0.4f, 10, 0},
        {13, -0.2f, 0, 1.5f, -0.2f, 0, -1.5f, -0.5f, -0.3f, 0.2f, 0, 0},
        {16.5f, -3.0f, -0.3f, 0.1f, -2.9f, 0.3f, -0.1f, -0.9f, -0.2f, -0.35f, -15, 0},
        {19.5f, 0.5f, 0.6f, 0, 0.4f, 0.4f, 0, -0.6f, 0.4f, 0.4f, 25, 0},
        {22, 1.1f, 0, 0.9f, 1.1f, 0, -0.9f, -1.4f, 0.9f, 0.1f, 30, 0.45f},
        {26, 0.4f, 0.3f, 1.25f, 0.4f, -0.3f, -1.25f, -1.55f, 0.35f, 0.35f, 12, 0.55f},
        {RISE, 0.45f, 0.3f, 1.3f, 0.45f, -0.3f, -1.3f, -1.55f, 0.35f, 0.4f, 14, 0.55f},
        {50, -0.4f, 0, 1.6f, 0.1f, 0, -0.4f, 0, 0, 0, 0, 0},
        {END, 0, 0, 0.1f, 0, 0, -0.1f, 0, 0, 0, 0, 0}};

    private static final float[][] ON = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {4, -1.6f, 0.2f, -0.3f, -1.5f, -0.2f, 0.3f, 0.2f, -0.3f, 0.1f, -5, 0},
        {10, -1.2f, 0, -0.2f, -1.1f, 0, 0.2f, 0.1f, -0.2f, -0.9f, -10, 0},
        {18, -1.25f, 0, -0.2f, -1.15f, 0, 0.2f, 0.1f, -0.2f, -1.0f, -12, 0},
        {20.5f, -0.4f, 0, 0.9f, -0.3f, 0, -0.9f, 0, 0, -0.6f, -18, 0},
        {24, -0.15f, 0, 0.25f, -0.1f, 0, -0.25f, 0.05f, -0.1f, 0.2f, 4, 0.05f},
        {KILL, -0.1f, 0, 0.2f, -0.05f, 0, -0.2f, 0.1f, -0.15f, 0.45f, 8, 0.12f},
        {END, -0.1f, 0, 0.2f, -0.05f, 0, -0.2f, 0.1f, -0.15f, 0.45f, 8, 0.12f}};

    private static float[] key(float[][] keys, float t) {
        float[] out = new float[keys[0].length - 1];
        for (int i = 0; i < keys.length - 1; i++) {
            float[] a = keys[i], b = keys[i + 1];
            if (t > b[0] && i < keys.length - 2) continue;
            float k = ease((t - a[0]) / Math.max(0.01f, b[0] - a[0]));
            for (int j = 1; j < a.length; j++) out[j - 1] = MathHelper.lerp(k, a[j], b[j]);
            return out;
        }
        float[] last = keys[keys.length - 1];
        System.arraycopy(last, 1, out, 0, out.length);
        return out;
    }

    /** The whole body: where it is on the path, which way it faces, the lean and the drop. True if it took over. */
    public static boolean transforms(LivingEntity e, MatrixStack ms, float bodyYaw, float td) {
        Scene s = of(e.getId());
        if (s == null) return false;
        float t = t(s);
        boolean by = e.getId() == s.by();
        Vec3d at = by ? path(s, t) : s.foe();
        float yaw = by ? facing(s, t) : s.yaw() + 180;
        float[] k = key(by ? BY : ON, t);
        // Back to the world's axes at the body's spot, over to where the scene has it, then turned its way.
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180 - bodyYaw)));
        Vec3d off = at.subtract(e.getLerpedPos(td));
        if (off.lengthSquared() < 64) ms.translate(off.x, off.y, off.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - yaw));
        float h = e.getHeight() * 0.5f;
        float breathe = by && t > SLIDE && t < RISE ? MathHelper.sin(t * 0.35f) * 0.015f : 0;
        if (!by && t > CUT && t < KILL) breathe = MathHelper.sin(t * 2.3f) * 0.01f * seg(t, CUT + 4, KILL);
        ms.translate(0, -k[10] + breathe, 0);
        ms.translate(0, h, 0);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-k[9]));
        ms.translate(0, -h, 0);
        return true;
    }

    /** The limbs. True if it took over. */
    public static boolean pose(BipedEntityModel<?> m, LivingEntity e) {
        Scene s = of(e.getId());
        if (s == null) return false;
        if (e.getId() == s.on() && e.deathTime > 0) return false;
        float[] k = key(e.getId() == s.by() ? BY : ON, t(s));
        m.rightArm.pitch = k[0];
        m.rightArm.yaw = k[1];
        m.rightArm.roll = k[2];
        m.leftArm.pitch = k[3];
        m.leftArm.yaw = k[4];
        m.leftArm.roll = k[5];
        m.rightLeg.pitch = k[6];
        m.leftLeg.pitch = k[7];
        m.rightLeg.yaw = m.leftLeg.yaw = 0;
        m.rightLeg.roll = 0.06f;
        m.leftLeg.roll = -0.06f;
        m.head.pitch = k[8];
        m.head.yaw = 0;
        m.body.pitch = 0;
        m.body.yaw = 0;
        sync(m);
        return true;
    }

    private static void sync(BipedEntityModel<?> m) {
        m.hat.copyTransform(m.head);
        if (m instanceof net.minecraft.client.render.entity.model.PlayerEntityModel<?> pm) {
            pm.jacket.copyTransform(pm.body);
            pm.rightSleeve.copyTransform(pm.rightArm);
            pm.leftSleeve.copyTransform(pm.leftArm);
            pm.rightPants.copyTransform(pm.rightLeg);
            pm.leftPants.copyTransform(pm.leftLeg);
        }
    }

    // ------------------------------------------------------------------ trails

    private static final Map<Integer, Float> lastFx = new HashMap<>();

    /** Wind off the blades along the path: streaks in the dash and the slide, sparks in the spin. */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || scenes.isEmpty()) return;
        for (Scene s : new java.util.ArrayList<>(scenes.values())) {
            if (scenes.get(s.by()) != s || of(s.by()) == null) continue;
            float t = t(s);
            Float was = lastFx.get(s.by());
            if (was != null && t - was < 0.9f) continue;
            lastFx.put(s.by(), t);
            Vec3d p = path(s, t).add(0, 1.1, 0), d = s.dir();
            Entity a = mc.world.getEntityById(s.by());
            boolean fast = t < DASH || t > CUT && t < SLIDE;
            if (fast) {
                for (int i = 0; i < 3; i++) {
                    FxParticle.spawn(AotParticles.STREAK, 0xFFFFFF, 0.9f, p.x + (Math.random() - 0.5) * 0.6, p.y + (Math.random() - 0.5) * 0.8,
                        p.z + (Math.random() - 0.5) * 0.6, -d.x * 0.6, 0, -d.z * 0.6);
                }
                if (a != null) Afterimages.leaveAt(a, path(s, t), facing(s, t), 0xFF3A2A, 380);
            } else if (t >= DASH && t < LEAP) {
                double ang = Math.toRadians(facing(s, t));
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    double bx = p.x + Math.cos(ang) * 1.1 * sgn, bz = p.z + Math.sin(ang) * 1.1 * sgn;
                    FxParticle.spawn(AotParticles.SPARK, 0xFFE8D0, 0.5f, bx, p.y, bz, 0, 0.02, 0);
                }
                if (a != null && (int) t % 2 == 0) Afterimages.leaveAt(a, path(s, t), facing(s, t), 0xFFFFFF, 300);
            }
        }
        lastFx.keySet().removeIf(id -> !scenes.containsKey(id));
    }

    // ------------------------------------------------------------------ camera and controls

    /** Holds still while it plays. */
    public static void steer(Input in) {
        if (!mine()) return;
        in.movementForward = 0;
        in.movementSideways = 0;
        in.pressingForward = in.pressingBack = in.pressingLeft = in.pressingRight = false;
        in.jumping = false;
        in.sneaking = false;
    }

    /**
     * The scene's own camera, in shots: side-on for the dash, low under them looking up into the
     * leap, tight on the cut (with a shudder), a slow circle round the two in the held breath,
     * then pulling back and easing into your own view as it ends.
     */
    public static com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td, Vec3d normal, float nyaw, float npitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;
        Scene s = of(mc.player.getId());
        if (s == null) return null;
        float t = t(s);
        Vec3d d = s.dir(), side = s.side(), v = s.foe().add(0, 1.2, 0), a = path(s, t).add(0, 1.2, 0);
        Vec3d pos, look;
        float shake = 0;
        if (t < DASH) {
            Vec3d mid = a.lerp(v, 0.5);
            pos = mid.add(side.multiply(4.4)).add(0, 0.5, 0).subtract(d.multiply(0.6));
            look = mid;
        } else if (t < LEAP + 1) {
            // From low behind them, looking up at the leap.
            pos = s.foe().add(d.multiply(2.6)).add(side.multiply(1.4)).add(0, 0.35, 0);
            look = a.lerp(v, 0.25);
        } else if (t < CUT + 2) {
            pos = v.subtract(d.multiply(0.3)).add(side.multiply(2.4)).add(0, 0.5, 0);
            look = v;
            shake = 1 - seg(t, CUT, CUT + 2);
        } else {
            // The held breath: a slow circle round them both, then pulling back.
            Vec3d mid = s.foe().add(d.multiply(0.95)).add(0, 1.0, 0);
            float k = seg(t, CUT + 2, END);
            double ang = Math.atan2(side.z, side.x) - 0.5 + k * 1.6;
            double r = 3.4 + 2.4 * seg(t, RISE, END);
            pos = mid.add(Math.cos(ang) * r, 0.4 + 1.6 * seg(t, RISE, END), Math.sin(ang) * r);
            look = mid;
        }
        // Kept out of walls.
        var hit = mc.world.raycast(new RaycastContext(look, pos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) pos = look.lerp(hit.getPos(), 0.85);
        Vec3d l = look.subtract(pos);
        float yaw = (float) (Math.toDegrees(Math.atan2(l.z, l.x)) - 90) + MathHelper.sin(t * 9) * 2f * shake;
        float pitch = (float) -Math.toDegrees(Math.atan2(l.y, Math.sqrt(l.x * l.x + l.z * l.z))) + MathHelper.cos(t * 11) * 2f * shake;
        // Eased in over the first beat and back out to your own view over the last.
        float w = Math.min(seg(t, 0, 2), 1 - seg(t, END - 7, END));
        if (w < 1 && normal != null) {
            pos = normal.lerp(pos, w);
            yaw = nyaw + MathHelper.wrapDegrees(yaw - nyaw) * w;
            pitch = MathHelper.lerp(w, npitch, pitch);
        }
        return new com.pglol.aotrpg.client.story.CutscenePlayer.Cam(pos.x, pos.y, pos.z, yaw, pitch);
    }
}
