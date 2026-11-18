package com.pglol.aotrpg.client;

import com.pglol.aotrpg.AotParticles;
import com.pglol.aotrpg.FinisherStyles;
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
 * A finisher as a scene everyone nearby sees, played through start to finish. Which one is the
 * finishing player's pick (a cosmetic): its timing is shared with the server (FinisherStyles),
 * its choreography is keyframes (FinisherMoves), played here: the body's path eased through its
 * points, the limbs and the whole body (lean, drop, turn, flips, the drill's twist) between
 * poses, wind and sparks off the blades, and a camera of its own for the two in it, cut like a
 * fight in an anime, easing back into the ordinary view at the end.
 */
public final class FinishScene {
    private FinishScene() {}

    private static final class Scene {
        final int by, on, style;
        final long at;
        final Vec3d from, foe, dir;
        final float yaw;
        final FinisherMoves.Move mv;
        final FinisherStyles.Style st;
        int fired;
        boolean fell;

        Scene(int by, int on, int style, long at, Vec3d from, Vec3d foe, Vec3d dir, float yaw) {
            this.by = by;
            this.on = on;
            this.style = style;
            this.at = at;
            this.from = from;
            this.foe = foe;
            this.dir = dir;
            this.yaw = yaw;
            this.mv = FinisherMoves.of(style);
            this.st = FinisherStyles.at(style);
        }

        Vec3d side() {
            return new Vec3d(-dir.z, 0, dir.x);
        }
    }

    /** Each one in a scene, by entity id. */
    private static final Map<Integer, Scene> scenes = new HashMap<>();

    public static void start(int by, int on) {
        start(by, on, 0);
    }

    public static void start(int by, int on, int style) {
        var mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Entity a = mc.world.getEntityById(by), b = mc.world.getEntityById(on);
        if (a == null || b == null) return;
        Vec3d from = a.getPos(), foe = b.getPos();
        Vec3d dir = foe.subtract(from).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0, a.getYaw()).multiply(1, 0, 1).normalize() : dir.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        Scene s = new Scene(by, on, style, Util.getMeasuringTimeMs(), from, foe, dir, yaw);
        stop(by);
        stop(on);
        dying.remove(on);
        scenes.put(by, s);
        scenes.put(on, s);
    }

    public static void stop(int id) {
        Scene s = scenes.get(id);
        if (s == null) return;
        scenes.remove(s.by);
        scenes.remove(s.on);
    }

    private static Scene of(int id) {
        Scene s = scenes.get(id);
        if (s == null) return null;
        if (realT(s) > s.st.realEnd()) {
            scenes.remove(s.by);
            scenes.remove(s.on);
            return null;
        }
        return s;
    }

    /** Real time into the scene (ticks). */
    private static float realT(Scene s) {
        return (Util.getMeasuringTimeMs() - s.at) / 50f;
    }

    /** Scene time (ticks): slowed through the killing blow, stopped dead as they fall (see FinisherStyles). */
    private static float t(Scene s) {
        return s.st.scene(realT(s));
    }

    /** 1 while the scene runs in slow motion (or is stopped), else 0, eased. */
    private static float slowness(Scene s) {
        float r = realT(s);
        float speed = s.st.scene(r + 0.75f) - s.st.scene(r - 0.75f);
        return MathHelper.clamp((1.5f - speed) / 0.9f, 0, 1);
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

    private static Vec3d point(Scene s, FinisherMoves.Way w, Vec3d base, boolean start) {
        if (Float.isNaN(w.along())) return start ? s.from : base;
        return base.add(s.dir.multiply(w.along())).add(s.side().multiply(w.side())).add(0, w.up(), 0);
    }

    /** Eased through its points: a smooth curve through moving ones, still where it holds. */
    private static Vec3d curve(Scene s, java.util.List<FinisherMoves.Way> ways, Vec3d base, boolean start, float t) {
        int n = ways.size();
        if (n == 0) return base;
        if (t <= ways.get(0).t()) return point(s, ways.get(0), base, start);
        if (t >= ways.get(n - 1).t()) return point(s, ways.get(n - 1), base, start);
        int i = 0;
        while (i < n - 2 && t > ways.get(i + 1).t()) i++;
        Vec3d p1 = point(s, ways.get(i), base, start), p2 = point(s, ways.get(i + 1), base, start);
        float t1 = ways.get(i).t(), t2 = ways.get(i + 1).t(), dt = Math.max(0.01f, t2 - t1);
        Vec3d m1 = tangent(s, ways, base, start, i), m2 = tangent(s, ways, base, start, i + 1);
        float u = (t - t1) / dt, u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        return p1.multiply(h00).add(m1.multiply(h10 * dt)).add(p2.multiply(h01)).add(m2.multiply(h11 * dt));
    }

    private static Vec3d tangent(Scene s, java.util.List<FinisherMoves.Way> ways, Vec3d base, boolean start, int i) {
        if (i <= 0 || i >= ways.size() - 1) return Vec3d.ZERO;
        Vec3d a = point(s, ways.get(i - 1), base, start), p = point(s, ways.get(i), base, start), b = point(s, ways.get(i + 1), base, start);
        // Held still either side: no swing through it.
        if (a.squaredDistanceTo(p) < 1e-4 || b.squaredDistanceTo(p) < 1e-4) return Vec3d.ZERO;
        Vec3d m = b.subtract(a).multiply(1 / Math.max(0.01, ways.get(i + 1).t() - ways.get(i - 1).t()));
        return m.lengthSquared() > 1.0 ? m.normalize() : m;
    }

    /** Where the one finishing is at time t (feet). */
    private static Vec3d path(Scene s, float t) {
        return curve(s, s.mv.path, s.foe, true, t);
    }

    /** Where the foe is drawn (a reel pulls them in). */
    private static Vec3d foeAt(Scene s, float t) {
        Vec3d v = s.mv.foe.isEmpty() ? s.foe : curve(s, s.mv.foe, s.foe, false, t);
        // Rocked back by each blow, and back again.
        float kick = 0;
        for (int c : s.st.cuts()) if (t >= c && t < c + 4) kick = Math.max(kick, MathHelper.sin((t - c) / 4 * MathHelper.PI));
        return kick > 0 ? v.add(s.dir.multiply(0.3 * kick)) : v;
    }

    /** Which way the one finishing faces: along the line, or kept to the foe; plus the move's turns. */
    private static float facing(Scene s, float t, float[] k) {
        float base = s.yaw;
        if (s.mv.faceFoe && t > 4) {
            Vec3d d = foeAt(s, t).subtract(path(s, t));
            if (d.horizontalLengthSquared() > 0.04) base = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        }
        return base + k[11];
    }

    // ------------------------------------------------------------------ poses

    /** The row at time t: rArm p/y/r, lArm p/y/r, rLeg, lLeg, head, lean, drop, yaw, flip, roll. */
    private static float[] key(java.util.List<float[]> keys, float t) {
        float[] out = new float[14];
        if (keys.isEmpty()) return out;
        if (keys.size() == 1 || t <= keys.get(0)[0]) {
            System.arraycopy(keys.get(0), 1, out, 0, 14);
            return out;
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            float[] a = keys.get(i), b = keys.get(i + 1);
            if (t > b[0] && i < keys.size() - 2) continue;
            float k = ease((t - a[0]) / Math.max(0.01f, b[0] - a[0]));
            for (int j = 1; j < 15; j++) out[j - 1] = MathHelper.lerp(k, a[j], b[j]);
            return out;
        }
        System.arraycopy(keys.get(keys.size() - 1), 1, out, 0, 14);
        return out;
    }

    /** The whole body: where it is, which way it faces, the lean, the drop, flips and twists. True if it took over. */
    public static boolean transforms(LivingEntity e, MatrixStack ms, float bodyYaw, float td) {
        Scene s = of(e.getId());
        if (s == null) return false;
        float t = t(s);
        boolean by = e.getId() == s.by;
        float[] k = key(by ? s.mv.by : s.mv.on, t);
        Vec3d at = by ? path(s, t) : foeAt(s, t);
        float yaw = by ? facing(s, t, k) : s.yaw + 180;
        // Back to the world's axes at the body's spot, over to where the scene has it, then turned its way.
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180 - bodyYaw)));
        Vec3d off = at.subtract(e.getLerpedPos(td));
        if (off.lengthSquared() < 100) ms.translate(off.x, off.y, off.z);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - yaw));
        float h = e.getHeight() * 0.5f;
        int last = s.st.lastCut(), kill = s.st.kill();
        float breathe = by && t > last + 3 && t < kill + 6 ? MathHelper.sin(t * 0.35f) * 0.015f : 0;
        if (!by && t > last && t < kill) breathe = MathHelper.sin(t * 2.3f) * 0.012f * seg(t, last + 3, kill);
        ms.translate(0, -k[10] + breathe, 0);
        // Squash and stretch: long and thin flying fast, squashed by every blow.
        float sx = 1, sy = 1;
        if (by) {
            double speed = at.distanceTo(path(s, Math.max(0, t - 0.5f))) * 2;
            float str = MathHelper.clamp((float) (speed - 0.35) / 1.2f, 0, 0.9f);
            sx -= 0.1f * str;
            sy += 0.22f * str;
        }
        for (int c : s.st.cuts()) {
            if (t < c || t > c + 2.5f) continue;
            float q = 1 - (t - c) / 2.5f;
            q = q * q * (by ? 0.13f : 0.2f);
            sx += q;
            sy -= q;
        }
        if (sx != 1 || sy != 1) ms.scale(sx, sy, sx);
        ms.translate(0, h, 0);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-k[9] * (by ? 1.3f : 1.15f)));
        if (by && k[12] != 0) ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-k[12]));
        if (by && k[13] != 0) ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(k[13]));
        ms.translate(0, -h, 0);
        return true;
    }

    /** The limbs. True if it took over. */
    public static boolean pose(BipedEntityModel<?> m, LivingEntity e) {
        Scene s = of(e.getId());
        if (s == null) return false;
        if (e.getId() == s.on && e.deathTime > 0) return false;
        float[] k = key(e.getId() == s.by ? s.mv.by : s.mv.on, t(s));
        // Every pose pushed further: bigger wind-ups, wider swings.
        float amp = e.getId() == s.by ? 1.22f : 1.12f;
        for (int i = 0; i < 9; i++) k[i] *= amp;
        k[0] = MathHelper.clamp(k[0], -3.4f, 2.1f);
        k[3] = MathHelper.clamp(k[3], -3.4f, 2.1f);
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

    // ------------------------------------------------------------------ trails and blows

    private static final Map<Integer, Float> lastFx = new HashMap<>();

    /**
     * Wind off the blades along the path (streaks and afterimages when moving fast, sparks when
     * spinning), and every blow landing: an arc of light across them and sparks, the last one
     * bigger, the spear's blast, the Rumbling's rings in the ground.
     */
    public static void tick(MinecraftClient mc) {
        if (mc.world == null || scenes.isEmpty()) return;
        for (Scene s : new java.util.ArrayList<>(scenes.values())) {
            if (scenes.get(s.by) != s || of(s.by) == null) continue;
            float t = t(s);
            Entity a = mc.world.getEntityById(s.by);
            boolean mine = mc.player != null && (mc.player.getId() == s.by || mc.player.getId() == s.on);
            int[] cuts = s.st.cuts();
            while (s.fired < cuts.length && t >= cuts[s.fired]) {
                blow(mc, s, s.fired == cuts.length - 1, s.fired, mine);
                s.fired++;
            }
            if (!s.fell && t >= s.st.kill()) {
                s.fell = true;
                kill(mc, s, mine);
            }
            float rt = realT(s), slow = slowness(s);
            Float was = lastFx.get(s.by);
            if (was != null && rt - was < 0.9f) continue;
            lastFx.put(s.by, rt);
            // In slow motion, a trail of ghosts behind every move.
            if (slow > 0.5f && a != null && t < s.st.kill()) Afterimages.leaveAt(a, path(s, t), facing(s, t, key(s.mv.by, t)), s.st.color(), 520);
            Vec3d now = path(s, t), before = path(s, Math.max(0, t - 1));
            float[] k = key(s.mv.by, t), kb = key(s.mv.by, Math.max(0, t - 1));
            double speed = now.distanceTo(before);
            float spin = Math.abs(k[11] - kb[11]) + Math.abs(k[12] - kb[12]) + Math.abs(k[13] - kb[13]);
            Vec3d p = now.add(0, 1.1, 0), d = now.subtract(before);
            float face = facing(s, t, k);
            if (speed > 0.35) {
                Vec3d back = speed > 1e-3 ? d.normalize().multiply(-0.6) : s.dir.multiply(-0.6);
                for (int i = 0; i < 3; i++) {
                    FxParticle.spawn(AotParticles.STREAK, 0xFFFFFF, 0.9f, p.x + (Math.random() - 0.5) * 0.6, p.y + (Math.random() - 0.5) * 0.8,
                        p.z + (Math.random() - 0.5) * 0.6, back.x, back.y, back.z);
                }
                if (a != null) Afterimages.leaveAt(a, now, face, s.st.color2(), 380);
            } else if (spin > 25) {
                double ang = Math.toRadians(face);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    double bx = p.x + Math.cos(ang) * 1.1 * sgn, bz = p.z + Math.sin(ang) * 1.1 * sgn;
                    FxParticle.spawn(AotParticles.SPARK, s.st.color(), 0.5f, bx, p.y, bz, 0, 0.02, 0);
                }
                if (a != null && (int) t % 2 == 0) Afterimages.leaveAt(a, now, face, s.st.color(), 300);
            }
        }
        lastFx.keySet().removeIf(id -> !scenes.containsKey(id));
    }

    private static void blow(MinecraftClient mc, Scene s, boolean last, int n, boolean mine) {
        float t = t(s);
        Vec3d v = foeAt(s, t).add(0, 1.15, 0), side = s.side();
        FxParticle.spawn(AotParticles.ARC, s.st.color(), last ? 2.6f : 1.6f, v.x, v.y, v.z, side.x * (n % 2 == 0 ? 1 : -1), 0, side.z);
        int sparks = last ? 14 : 6;
        for (int i = 0; i < sparks; i++) {
            FxParticle.spawn(AotParticles.SPARK, i % 3 == 0 ? s.st.color2() : 0xFFF4E8, 0.5f, v.x, v.y, v.z,
                (Math.random() - 0.5) * 0.6, (Math.random() - 0.3) * 0.4, (Math.random() - 0.5) * 0.6);
        }
        if (last) FxParticle.spawn(AotParticles.FLASH, s.st.color(), 1.6f, v.x, v.y, v.z, 0, 0, 0);
        // A slash across the screen for everyone near enough to see it.
        if (mc.player != null) {
            double d = mc.player.getPos().distanceTo(v);
            float near = mine ? 1 : (float) MathHelper.clamp(1 - d / 24, 0, 0.6);
            if (near > 0.05f) slash((n % 2 == 0 ? -0.5f : 0.5f) + (float) (Math.random() - 0.5) * 0.5f, s.st.color(), (last ? 1.2f : 0.6f) * near);
        }
        String id = s.st.id();
        if (id.equals("fin_spear") && last) {
            // The blast.
            FxParticle.spawn(AotParticles.BURST, 0xFFD24A, 3.5f, v.x, v.y, v.z, 0, 0, 0);
            FxParticle.spawn(AotParticles.FLASH, 0xFFF0C0, 4f, v.x, v.y, v.z, 0, 0, 0);
            for (int i = 0; i < 16; i++) {
                FxParticle.spawn(AotParticles.PUFF, 0x5A5048, 1.4f, v.x, v.y, v.z,
                    (Math.random() - 0.5) * 0.5, Math.random() * 0.25, (Math.random() - 0.5) * 0.5);
            }
            if (mine) EffectFx.quake(0.9f, 12);
        } else if (id.equals("fin_rumble")) {
            Vec3d g = foeAt(s, t);
            FxParticle.spawn(AotParticles.RING, 0xC8B090, 2.5f + n * 1.4f, g.x, g.y + 0.05, g.z, 0, 0, 0);
            for (int i = 0; i < 12; i++) {
                double ang = i * Math.PI * 2 / 12;
                FxParticle.spawn(AotParticles.PUFF, 0x8A7A68, 1.2f, g.x + Math.cos(ang) * 1.2, g.y + 0.1, g.z + Math.sin(ang) * 1.2,
                    Math.cos(ang) * 0.2, 0.08, Math.sin(ang) * 0.2);
            }
            if (mine) EffectFx.quake(0.5f + n * 0.25f, 10);
        } else if (mine) EffectFx.quake(last ? 0.45f : 0.15f, last ? 8 : 3);
    }

    // ------------------------------------------------------------------ the kill

    private static final int SLUMP = 0, BLOWN = 1, SPIN = 2, SLAM = 3;

    private record Dying(Vec3d dir, float yaw, int kind, long at, Vec3d from, boolean[] landed) { }

    /** Those just finished, falling the scene's way (by entity id). */
    private static final Map<Integer, Dying> dying = new HashMap<>();

    private static int deathKind(String id) {
        return switch (id) {
            case "fin_spear", "fin_drill", "fin_rumble", "fin_rising", "fin_grapple" -> BLOWN;
            case "fin_whirl", "fin_flurry", "fin_cross" -> SPIN;
            case "fin_heel" -> SLAM;
            default -> SLUMP;
        };
    }

    private static double rr() {
        return Math.random() - 0.5;
    }

    /**
     * They fall: the screen flashes and stops dead, rings of force run out over the ground, a
     * pillar of light, a fan of red along the cut, sparks raining down, and each finisher's own
     * signature on top (the spear's fireball, the Rumbling tearing the ground open, a tornado of
     * steel, the Thousand Cuts all showing at once, steam off a cut nape...).
     */
    private static void kill(MinecraftClient mc, Scene s, boolean mine) {
        float t = t(s);
        Vec3d v = foeAt(s, t), c = v.add(0, 1.1, 0), d = s.dir, side = s.side();
        int c1 = s.st.color(), c2 = s.st.color2();
        dying.put(s.on, new Dying(d, s.yaw, deathKind(s.st.id()), Util.getMeasuringTimeMs(), v, new boolean[1]));
        if (dying.size() > 32) dying.values().removeIf(x -> Util.getMeasuringTimeMs() - x.at() > 3000);
        for (int i = 0; i < 3; i++) FxParticle.spawn(AotParticles.RING, i == 0 ? 0xFFFFFF : i == 1 ? c1 : c2, 2.5f + i * 2.4f, v.x, v.y + 0.05 + i * 0.02, v.z, 0, 0, 0);
        for (int i = 0; i < 6; i++) FxParticle.spawn(AotParticles.FLASH, i % 2 == 0 ? 0xFFFFFF : c1, 2.4f - i * 0.25f, v.x, v.y + 0.4 + i * 0.75, v.z, 0, 0.02, 0);
        FxParticle.spawn(AotParticles.BURST, c1, 5.5f, c.x, c.y, c.z, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            double a = rr() * 1.5, sp = 0.3 + Math.random() * 0.55;
            Vec3d f = d.multiply(Math.cos(a)).add(side.multiply(Math.sin(a)));
            FxParticle.spawn(AotParticles.SHARD, i % 3 == 0 ? c2 : 0xB01A1A, 0.5f + (float) Math.random() * 0.4f, c.x, c.y, c.z, f.x * sp, 0.1 + Math.random() * 0.35, f.z * sp);
        }
        for (int i = 0; i < 26; i++) {
            FxParticle.spawn(AotParticles.SPARK, i % 2 == 0 ? c1 : 0xFFF4E0, 0.55f, c.x + rr() * 0.6, c.y + 1.5 + Math.random(), c.z + rr() * 0.6, rr() * 0.4, 0.15 + Math.random() * 0.3, rr() * 0.4);
        }
        for (int i = 0; i < 18; i++) {
            double a = i * Math.PI * 2 / 18;
            FxParticle.spawn(AotParticles.STREAK, 0xFFFFFF, 1.2f, c.x, c.y - 0.5, c.z, Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9);
        }
        switch (s.st.id()) {
            case "fin_spear" -> {
                for (int i = 0; i < 3; i++) FxParticle.spawn(AotParticles.BURST, i == 1 ? 0xFFF0C0 : 0xFFA030, 6f + i * 2, c.x + rr(), c.y + rr(), c.z + rr(), 0, 0, 0);
                for (int i = 0; i < 46; i++) {
                    FxParticle.spawn(AotParticles.PUFF, i % 3 == 0 ? 0x8A5030 : 0x3A3430, 1.6f + (float) Math.random(), c.x, c.y, c.z, rr() * 0.8, Math.random() * 0.4, rr() * 0.8);
                }
                for (int i = 0; i < 30; i++) FxParticle.spawn(AotParticles.SPARK, 0xFF8A2A, 0.6f, c.x, c.y, c.z, rr() * 0.9, Math.random() * 0.7, rr() * 0.9);
                if (mine) EffectFx.quake(1.3f, 18);
            }
            case "fin_rumble" -> {
                for (int i = 0; i < 5; i++) FxParticle.spawn(AotParticles.RING, 0xC8B090, 4f + i * 3.5f, v.x, v.y + 0.05, v.z, 0, 0, 0);
                for (int i = 0; i < 48; i++) {
                    double a = Math.random() * Math.PI * 2, r = 1 + Math.random() * 4;
                    FxParticle.spawn(AotParticles.PUFF, i % 2 == 0 ? 0x8A7A68 : 0x5A4A3A, 1.8f, v.x + Math.cos(a) * r, v.y + 0.1, v.z + Math.sin(a) * r,
                        Math.cos(a) * 0.15, 0.25 + Math.random() * 0.35, Math.sin(a) * 0.15);
                }
                FxParticle.spawn(AotParticles.FLASH, 0xE02A2A, 8f, c.x, c.y, c.z, 0, 0, 0);
                if (mine) EffectFx.quake(1.5f, 24);
            }
            case "fin_whirl" -> {
                for (int i = 0; i < 48; i++) {
                    double a = i * 0.55, r = 0.4 + i * 0.035;
                    FxParticle.spawn(AotParticles.STREAK, i % 2 == 0 ? c1 : 0xFFFFFF, 1f, v.x + Math.cos(a) * r, v.y + i * 0.09, v.z + Math.sin(a) * r,
                        -Math.sin(a) * 0.5, 0.05, Math.cos(a) * 0.5);
                }
            }
            case "fin_flurry" -> {
                // Every cut shows at once.
                for (int i = 0; i < 16; i++) {
                    FxParticle.spawn(AotParticles.ARC, 0xFFFFFF, 1.4f + (float) Math.random(), c.x + rr() * 0.8, c.y + rr() * 1.2, c.z + rr() * 0.8, rr(), rr(), rr());
                }
            }
            case "fin_cross" -> {
                FxParticle.spawn(AotParticles.ARC, c1, 4.5f, c.x, c.y, c.z, side.x, 0.8, side.z);
                FxParticle.spawn(AotParticles.ARC, c2, 4.5f, c.x, c.y, c.z, side.x, -0.8, side.z);
            }
            case "fin_shadow" -> {
                FxParticle.spawn(AotParticles.BURST, 0x2A2040, 7f, c.x, c.y, c.z, 0, 0, 0);
                for (int i = 0; i < 36; i++) FxParticle.spawn(AotParticles.SHARD, i % 2 == 0 ? 0x1A1028 : 0x8A7AB0, 0.6f, c.x, c.y, c.z, rr() * 0.3, Math.random() * 0.12, rr() * 0.3);
            }
            case "fin_salute" -> {
                // Wings of freedom: two sweeps of white and green, out and up.
                for (int i = 0; i < 40; i++) {
                    int sg = i % 2 == 0 ? 1 : -1;
                    double k = (i / 2) / 20.0;
                    Vec3d o = side.multiply(sg * (0.3 + k * 2.2)).add(0, 0.2 + Math.sin(k * Math.PI) * 1.4, 0);
                    FxParticle.spawn(AotParticles.SHARD, i % 4 < 2 ? 0xF4F0E6 : 0x5E8C4A, 0.7f, c.x, c.y, c.z, o.x * 0.25, o.y * 0.25, o.z * 0.25);
                }
            }
            case "fin_heel" -> {
                for (int i = 0; i < 20; i++) FxParticle.spawn(AotParticles.STREAK, 0xFFE8A0, 1.4f, v.x + rr() * 0.4, v.y + 6 - i * 0.3, v.z + rr() * 0.4, 0, -1.2, 0);
                for (int i = 0; i < 4; i++) FxParticle.spawn(AotParticles.RING, 0xF0A030, 3f + i * 2.5f, v.x, v.y + 0.05, v.z, 0, 0, 0);
            }
            case "fin_drill" -> {
                for (int i = 0; i < 40; i++) {
                    double a = i * 0.7, along = i * 0.12 - 1;
                    Vec3d o = d.multiply(along).add(side.multiply(Math.cos(a) * 0.6)).add(0, Math.sin(a) * 0.6, 0);
                    FxParticle.spawn(AotParticles.STREAK, i % 2 == 0 ? c1 : 0xFFFFFF, 1f, c.x + o.x, c.y + o.y, c.z + o.z, d.x * 0.8, 0, d.z * 0.8);
                }
            }
            case "fin_nape" -> {
                // Steam, as off a titan's nape.
                for (int i = 0; i < 34; i++) FxParticle.spawn(AotParticles.PUFF, 0xF4F0EA, 1.6f + (float) Math.random(), c.x + rr(), c.y + 0.3, c.z + rr(), rr() * 0.15, 0.12 + Math.random() * 0.2, rr() * 0.15);
                FxParticle.spawn(AotParticles.ARC, 0xFFFFFF, 5f, c.x, c.y + 0.3, c.z, side.x, 0, side.z);
            }
            case "fin_grapple" -> {
                Vec3d a = path(s, t).add(0, 1.2, 0);
                for (int i = 0; i <= 14; i++) {
                    Vec3d p = a.lerp(c, i / 14.0);
                    FxParticle.spawn(AotParticles.SPARK, 0xC8C0B0, 0.4f, p.x, p.y, p.z, rr() * 0.2, 0.1, rr() * 0.2);
                }
            }
            case "fin_rising" -> {
                for (int i = 0; i < 24; i++) FxParticle.spawn(AotParticles.STREAK, i % 2 == 0 ? c1 : c2, 1.3f, v.x + rr() * 0.5, v.y + i * 0.2, v.z + rr() * 0.5, 0, 1.2, 0);
                FxParticle.spawn(AotParticles.ARC, c1, 4.5f, c.x, c.y + 0.6, c.z, 0, 1, 0);
            }
            default -> {
                FxParticle.spawn(AotParticles.BURST, 0xB01A1A, 5f, c.x, c.y, c.z, 0, 0, 0);
                FxParticle.spawn(AotParticles.RING, 0xB01A1A, 5f, v.x, v.y + 0.06, v.z, 0, 0, 0);
            }
        }
        // On screen: a flash, the hit-stop, two slashes crossing.
        if (mc.player != null) {
            double dist = mc.player.getPos().distanceTo(c);
            float near = mine ? 1 : (float) MathHelper.clamp(1 - dist / 28, 0, 0.7);
            if (near > 0.05f) {
                ImpactFrames.hit(c, 1.3f * near);
                flash(c1, near);
                slash(-0.6f, 0xFFFFFF, 1.6f * near);
                slash(0.55f, c1, 1.4f * near);
            }
            if (mine) EffectFx.quake(0.9f, 14);
        }
    }

    /**
     * Their fall, the scene's way: blown back off their feet in a backflip and down on their back
     * (the spear, the drill, the Rumbling...), spun round and dropped (the whirls and flurries),
     * smashed flat (the Falling Star), or left to slump to their knees and pitch forward.
     */
    public static boolean death(LivingEntity e, MatrixStack ms, float bodyYaw, float td) {
        Dying d = dying.get(e.getId());
        if (d == null || d.kind() == SLUMP) return false;
        if (Util.getMeasuringTimeMs() - d.at() > 4000) {
            dying.remove(e.getId());
            return false;
        }
        float p = MathHelper.clamp((e.deathTime + td - 1) / 18f, 0, 1), h = e.getHeight() * 0.5f;
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(180 - bodyYaw)));
        switch (d.kind()) {
            case BLOWN -> {
                float fl = 1 - (1 - p) * (1 - p);
                float up = p < 0.7f ? MathHelper.sin(p / 0.7f * MathHelper.PI) * 1.7f : 0;
                Vec3d off = d.dir().multiply(4.8 * fl);
                ms.translate(off.x, up, off.z);
                ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - (d.yaw() + 180)));
                float lie = ease((p - 0.62f) / 0.3f);
                ms.translate(0, -(h - 0.15f) * lie, 0);
                ms.translate(0, h, 0);
                ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(Math.min(1, p / 0.7f) * 360 + 90 * lie));
                ms.translate(0, -h, 0);
            }
            case SPIN -> {
                float fall = ease((p - 0.5f) / 0.45f);
                ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - (d.yaw() + 180) + 720 * ease(Math.min(1, p / 0.6f))));
                ms.translate(0, -(h - 0.18f) * fall, 0);
                ms.translate(0, h, 0);
                ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(90 * fall));
                ms.translate(0, -h, 0);
            }
            default -> { // SLAM
                float fall = ease(Math.min(1, p / 0.22f));
                ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - (d.yaw() + 180)));
                ms.translate(0, -(h - 0.1f) * fall, 0);
                ms.translate(0, h, 0);
                ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90 * fall));
                ms.translate(0, -h, 0);
                if (p < 0.35f) ms.scale(1 + 0.15f * fall, 1 - 0.15f * fall, 1 + 0.15f * fall);
            }
        }
        // A thump of dust where the blown-away land.
        if (d.kind() == BLOWN && p > 0.7f && !d.landed()[0]) {
            d.landed()[0] = true;
            Vec3d at = d.from().add(d.dir().multiply(4.8));
            FxParticle.spawn(AotParticles.RING, 0xD8C8A8, 2.2f, at.x, at.y + 0.05, at.z, 0, 0, 0);
            for (int i = 0; i < 10; i++) FxParticle.spawn(AotParticles.PUFF, 0x8A7A68, 1.2f, at.x + rr(), at.y + 0.1, at.z + rr(), rr() * 0.2, 0.08, rr() * 0.2);
        }
        return true;
    }

    // ------------------------------------------------------------------ on screen

    private record Slash(long at, float angle, int rgb, float strength) { }

    private static final java.util.List<Slash> slashes = new java.util.ArrayList<>();
    private static long flashAt;
    private static int flashRgb;
    private static float flashK;

    private static void slash(float angle, int rgb, float strength) {
        slashes.add(new Slash(Util.getMeasuringTimeMs(), angle, rgb, strength));
        while (slashes.size() > 8) slashes.remove(0);
    }

    private static void flash(int rgb, float k) {
        flashAt = Util.getMeasuringTimeMs();
        flashRgb = rgb;
        flashK = k;
    }

    /** Cinema bars while you're in one, a darkening in the slow motion, slashes across the screen and the flash of the kill. */
    public static void hud(net.minecraft.client.gui.DrawContext c, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        int W = c.getScaledWindowWidth(), H = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        Scene s = of(mc.player.getId());
        if (s != null) {
            float rt = realT(s), end = s.st.realEnd();
            float w = Math.min(seg(rt, 0, 6), 1 - seg(rt, end - 8, end));
            int bh = (int) (H * 0.09f * w);
            c.fill(0, 0, W, bh, 0xFF000000);
            c.fill(0, H - bh, W, H, 0xFF000000);
            float sl = slowness(s) * w;
            if (sl > 0.01f) {
                int a = (int) (110 * sl);
                c.fillGradient(0, bh, W, bh + H / 4, a << 24 | 0x140404, 0);
                c.fillGradient(0, H - bh - H / 4, W, H - bh, 0, a << 24 | 0x140404);
            }
        }
        float cx = W / 2f, cy = H / 2f;
        for (var it = slashes.iterator(); it.hasNext(); ) {
            Slash sl = it.next();
            float age = (now - sl.at()) / 1000f, life = 0.45f;
            if (age > life) {
                it.remove();
                continue;
            }
            float grow = Math.min(1, age / 0.07f), fade = 1 - age / life;
            float len = W * 0.75f * grow, th = 5 * sl.strength() * fade + 0.5f;
            float dx = MathHelper.cos(sl.angle()), dy = MathHelper.sin(sl.angle());
            float nx = -dy, ny = dx;
            float x0 = cx - dx * len, y0 = cy - dy * len, x1 = cx + dx * len, y1 = cy + dy * len;
            int glowA = (int) (130 * fade * Math.min(1, sl.strength()));
            Smooth.poly(c, new float[] {x0, y0, cx + nx * th * 3, cy + ny * th * 3, x1, y1, cx - nx * th * 3, cy - ny * th * 3}, glowA << 24 | sl.rgb());
            Smooth.poly(c, new float[] {x0, y0, cx + nx * th, cy + ny * th, x1, y1, cx - nx * th, cy - ny * th}, (int) (240 * fade) << 24 | 0xFFFFFF);
        }
        float fa = (now - flashAt) / 260f;
        if (fa < 1 && flashK > 0) {
            int a = (int) (200 * (1 - fa) * flashK);
            c.fill(0, 0, W, H, a << 24 | (fa < 0.25f ? 0xFFFFFF : flashRgb));
        }
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
     * The scene's own camera, in shots: side-on for the approach, low looking up whenever the one
     * finishing is in the air, tight on every blow (with a shudder), round the two of them
     * between, a slow circle in the held breath, then pulling back and easing into your own view.
     */
    public static com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td, Vec3d normal, float nyaw, float npitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;
        Scene s = of(mc.player.getId());
        if (s == null) return null;
        float t = t(s);
        int end = s.st.end(), last = s.st.lastCut(), kill = s.st.kill();
        Vec3d d = s.dir, side = s.side(), foe = foeAt(s, t), v = foe.add(0, 1.2, 0), a = path(s, t).add(0, 1.2, 0);
        Vec3d pos, look;
        float shake = 0;
        int near = -1;
        for (int c : s.st.cuts()) if (t >= c - 2.5f && t < c + 2.5f) near = c;
        if (t < 5) {
            Vec3d mid = a.lerp(v, 0.5);
            pos = mid.add(side.multiply(4.4)).add(0, 0.5, 0).subtract(d.multiply(0.6));
            look = mid;
        } else if (near >= 0) {
            // In slow motion: in close, circling slowly round the blow.
            float sl = slowness(s), o = sl * (realT(s) - (s.st.lastCut() - 2)) * 0.05f;
            Vec3d sd = new Vec3d(side.x * MathHelper.cos(o) - side.z * MathHelper.sin(o), 0, side.x * MathHelper.sin(o) + side.z * MathHelper.cos(o));
            pos = v.subtract(d.multiply(0.3)).add(sd.multiply(2.5 - 0.9 * sl)).add(0, 0.5 + Math.max(0, a.y - v.y) * 0.4 - 0.2 * sl, 0);
            look = v.lerp(a, 0.25);
            shake = t >= near ? 1 - seg(t, near, near + 2.5f) : 0;
        } else if (a.y - v.y > 1.2) {
            // Low behind them, looking up at whoever is in the air.
            pos = foe.add(d.multiply(2.6)).add(side.multiply(1.6)).add(0, 0.35, 0);
            look = a.lerp(v, 0.25);
        } else if (t < last) {
            Vec3d mid = v.lerp(a, 0.4);
            double ang = Math.atan2(side.z, side.x) + 0.4 - seg(t, 5, last) * 0.9;
            pos = mid.add(Math.cos(ang) * 4.0, 0.7, Math.sin(ang) * 4.0);
            look = mid;
        } else {
            // The held breath: a slow circle round them both, then pulling back.
            Vec3d mid = v.lerp(a, 0.5).add(0, -0.2, 0);
            float k = seg(t, last + 2, end);
            double ang = Math.atan2(side.z, side.x) - 0.5 + k * 1.6;
            double r = 3.4 + 2.4 * seg(t, kill + 4, end);
            pos = mid.add(Math.cos(ang) * r, 0.4 + 1.6 * seg(t, kill + 4, end), Math.sin(ang) * r);
            look = mid;
        }
        // The hit-stop as they fall: the frame shudders.
        if (t >= kill && t <= kill + 0.01f) shake = 1.6f;
        // Kept out of walls.
        var hit = mc.world.raycast(new RaycastContext(look, pos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) pos = look.lerp(hit.getPos(), 0.85);
        Vec3d l = look.subtract(pos);
        float yaw = (float) (Math.toDegrees(Math.atan2(l.z, l.x)) - 90) + MathHelper.sin(t * 9) * 2f * shake;
        float pitch = (float) -Math.toDegrees(Math.atan2(l.y, Math.sqrt(l.x * l.x + l.z * l.z))) + MathHelper.cos(t * 11) * 2f * shake;
        // Eased in over the first beat and back out to your own view over the last.
        float rtt = realT(s), rend = s.st.realEnd();
        float w = Math.min(seg(rtt, 0, 2), 1 - seg(rtt, rend - 7, rend));
        if (w < 1 && normal != null) {
            pos = normal.lerp(pos, w);
            yaw = nyaw + MathHelper.wrapDegrees(yaw - nyaw) * w;
            pitch = MathHelper.lerp(w, npitch, pitch);
        }
        return new com.pglol.aotrpg.client.story.CutscenePlayer.Cam(pos.x, pos.y, pos.z, yaw, pitch);
    }
}
