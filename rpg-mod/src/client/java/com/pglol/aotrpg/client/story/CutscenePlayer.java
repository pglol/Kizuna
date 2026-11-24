package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.Ui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Cutscenes, shot like a camera operator would: the authored shots are only where the camera
 * <i>wants</i> to be. The real camera chases that on a critically damped spring (position, the
 * point it looks at, the lens and the tilt), so it eases out of your eyes with no jolt, rounds
 * every change of shot instead of turning a corner, carries its momentum through, and at the
 * end glides back into your own view and lets go the instant it's there. Only a shot far across
 * the world is a real cut, hidden in a quick blink. A light handheld drift keeps it alive.
 * <p>
 * It never stacks or restarts: one arriving while another plays waits its turn (the same one
 * twice is dropped). Holding jump skips it, straight back home on the same spring. A cutscene
 * with no shots is a glimpse: just the title card, the camera stays yours.
 */
public final class CutscenePlayer {
    private CutscenePlayer() {}

    public record Cam(double x, double y, double z, float yaw, float pitch) { }

    /** Where a shot wants the camera: position, the point it looks at, lens (0 = yours), tilt. */
    private record Want(Vec3d pos, Vec3d look, float fov, float roll) { }

    /** A shot this far from the last is a real cut; within it, the camera travels. */
    private static final float FAR = 40;
    /** Hard limit on the glide home, in case you've moved somewhere it can't settle. */
    private static final float HOME_MAX = 2.2f;
    private static final float SKIP_HOLD = 0.55f;

    private static List<Net.Shot> shots = List.of();
    private static final ArrayDeque<Net.Cutscene> queue = new ArrayDeque<>();
    private static long startedAt, endedAt, lastFrame, skipFrom;
    private static boolean fade, active, blendOut, closedBlack, skipped;
    private static float total, homeAt = -1;
    private static String title = "", sub = "";
    private static int color;

    // The camera itself: spring state.
    private static Vec3d pos, vel, look, lookVel;
    private static float fovNow, fovVel, rollNow, rollVel;
    private static int shotIndex;
    /** A flight between places far apart (no cuts): from where, looking where, when, how long. */
    private static Vec3d flyFrom, flyLookFrom;
    private static float flyStart = -1, flyDur;

    /** Swoops from the camera's current pose toward a shot far away, arcing up over whatever's between. */
    private static void fly(Vec3d from, Vec3d lookFrom, Vec3d to, float at) {
        flyFrom = from;
        flyLookFrom = lookFrom;
        flyStart = at;
        flyDur = (float) MathHelper.clamp(0.75 + from.distanceTo(to) / 140.0, 0.8, 1.7);
        var mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(com.pglol.aotrpg.HitSounds.WHOOSH, 0.8f, 0.55f));
    }

    private static double ease3(double k) {
        k = MathHelper.clamp(k, 0, 1);
        return k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
    }
    private static Cam out;
    /** The game's own lens this frame (before any cutscene), so the glide home lands on it exactly. */
    public static float vanillaFov = 70;

    // Glimpse: just the title card.
    private static long glimpseAt;
    private static String gTitle = "", gSub = "";
    private static int gColor;

    public static void reset() {
        active = false;
        shots = List.of();
        queue.clear();
        out = null;
        glimpseAt = 0;
    }

    public static boolean active() {
        return active;
    }

    public static void play(Net.Cutscene c) {
        // Intros switched off: just the title card (story scenes, which fade in, always play).
        if (!com.pglol.aotrpg.client.Settings.cutscenes && !c.fade() && !c.title().isEmpty() && c.blendOut()) c = new Net.Cutscene(java.util.List.of(), false, c.title(), c.sub(), c.color(), true);
        if (c.shots().isEmpty()) {
            if (!c.title().isEmpty()) {
                gTitle = c.title();
                gSub = c.sub();
                gColor = c.color() == 0 ? 0xE0B96A : c.color();
                glimpseAt = Util.getMeasuringTimeMs();
            }
            return;
        }
        if (active) {
            // Never restart what's playing: the same one again is dropped, anything else waits.
            boolean dup = !c.title().isEmpty() && c.title().equals(title);
            for (Net.Cutscene q : queue) if (!c.title().isEmpty() && q.title().equals(c.title())) dup = true;
            if (!dup && queue.size() < 2) queue.add(c);
            return;
        }
        start(c);
    }

    private static void start(Net.Cutscene c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        shots = c.shots();
        fade = c.fade();
        title = c.title();
        sub = c.sub();
        blendOut = c.blendOut();
        color = c.color() == 0 ? 0xE0B96A : c.color();
        total = 0;
        for (Net.Shot s : shots) total += s.seconds();
        startedAt = lastFrame = Util.getMeasuringTimeMs();
        skipped = false;
        skipFrom = 0;
        homeAt = -1;
        shotIndex = 0;
        active = !shots.isEmpty() && mc.player != null;
        if (!active) return;
        // The spring starts exactly where your eyes are, at rest.
        pos = mc.player.getCameraPosVec(1f);
        look = pos.add(mc.player.getRotationVec(1f).multiply(10));
        vel = Vec3d.ZERO;
        lookVel = Vec3d.ZERO;
        fovNow = vanillaFov;
        fovVel = 0;
        rollNow = rollVel = 0;
        // A story scene opens on its first shot (from black); anything else lifts out of your eyes,
        // flying there if it's far.
        flyStart = -1;
        Want first = want(shots.get(0), 0);
        if (fade) snapTo(shots.get(0));
        else if (first.pos().distanceTo(pos) > FAR) fly(pos, look, first.pos(), 0);
        com.pglol.aotrpg.client.HudHide.hide("cutscene");
    }

    private static void snapTo(Net.Shot s) {
        Want a = want(s, 0), b = want(s, 0.02);
        float dt = Math.max(0.01f, s.seconds()) * 0.02f;
        pos = a.pos();
        look = a.look();
        vel = b.pos().subtract(a.pos()).multiply(1 / dt);
        lookVel = b.look().subtract(a.look()).multiply(1 / dt);
        fovNow = a.fov() > 0 ? a.fov() : vanillaFov;
        rollNow = a.roll();
        fovVel = rollVel = 0;
    }

    private static float elapsed() {
        return (Util.getMeasuringTimeMs() - startedAt) / 1000f;
    }

    /** The authored part is over (or skipped): heading home. */
    private static boolean home() {
        return skipped || elapsed() >= total;
    }

    private static double smooth(double t) {
        t = MathHelper.clamp(t, 0, 1);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double ease(int kind, double t) {
        t = MathHelper.clamp(t, 0, 1);
        return switch (kind) {
            // Quick, slow through the middle, quick: the moment hangs.
            case 1 -> t + 0.72 * Math.sin(2 * Math.PI * t) / (2 * Math.PI);
            // A whoosh: most of the move at once, then a long settle.
            case 2 -> 1 - Math.pow(1 - t, 3);
            default -> smooth(t);
        };
    }

    private static Want want(Net.Shot s, double t) {
        double k = ease(s.ease(), t);
        Vec3d p;
        if (s.path() == 1) {
            // Round the point it ends up looking at: angle, radius and height each glide.
            double cx = s.mx(), cz = s.mz();
            double a0 = Math.atan2(s.fz() - cz, s.fx() - cx), a1 = Math.atan2(s.tz() - cz, s.tx() - cx);
            double da = MathHelper.wrapDegrees(Math.toDegrees(a1 - a0));
            double a = a0 + Math.toRadians(da) * k;
            double r = MathHelper.lerp(k, Math.hypot(s.fx() - cx, s.fz() - cz), Math.hypot(s.tx() - cx, s.tz() - cz));
            p = new Vec3d(cx + Math.cos(a) * r, MathHelper.lerp(k, s.fy(), s.ty()), cz + Math.sin(a) * r);
        } else {
            p = new Vec3d(MathHelper.lerp(k, s.fx(), s.tx()), MathHelper.lerp(k, s.fy(), s.ty()), MathHelper.lerp(k, s.fz(), s.tz()));
        }
        Vec3d l = new Vec3d(MathHelper.lerp(k, s.lx(), s.mx()), MathHelper.lerp(k, s.ly(), s.my()), MathHelper.lerp(k, s.lz(), s.mz()));
        float fov = s.fov0() > 0 && s.fov1() > 0 ? (float) MathHelper.lerp(k, s.fov0(), s.fov1()) : 0;
        return new Want(p, l, fov, (float) MathHelper.lerp(k, s.roll0(), s.roll1()));
    }

    /** Keeps the camera out of the terrain: pulled in toward what it's looking at if a wall is in the way. */
    private static Vec3d clear(Vec3d p, Vec3d lookAt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return p;
        var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(lookAt, p, net.minecraft.world.RaycastContext.ShapeType.VISUAL,
            net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return p;
        Vec3d back = p.subtract(lookAt);
        if (back.lengthSquared() < 1e-4) return p;
        return hit.getPos().subtract(back.normalize().multiply(0.35));
    }

    /** One implicit step of a critically damped spring (stable at any frame rate). Returns {x, v}. */
    private static Vec3d[] spring(Vec3d x, Vec3d v, Vec3d target, double w, double dt) {
        double f = 1 + 2 * dt * w, hoo = dt * w * w, hhoo = dt * hoo, inv = 1 / (f + hhoo);
        Vec3d nx = x.multiply(f).add(v.multiply(dt)).add(target.multiply(hhoo)).multiply(inv);
        Vec3d nv = v.add(target.subtract(x).multiply(hoo)).multiply(inv);
        return new Vec3d[] {nx, nv};
    }

    private static float[] spring(float x, float v, float target, double w, double dt) {
        double f = 1 + 2 * dt * w, hoo = dt * w * w, hhoo = dt * hoo, inv = 1 / (f + hhoo);
        return new float[] {(float) ((f * x + dt * v + hhoo * target) * inv), (float) ((v + hoo * (target - x)) * inv)};
    }

    /** Advances the camera to now. Called once per frame however many times the pose is asked for. */
    private static void advance(float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = Util.getMeasuringTimeMs();
        if (now == lastFrame && out != null) return;
        double dt = MathHelper.clamp((now - lastFrame) / 1000.0, 0, 0.1);
        lastFrame = now;
        float t = elapsed();
        Vec3d tp, tl;
        float tf, tr;
        double w;
        if (!home()) {
            // Which shot, and how far in.
            float left = t;
            int i = 0;
            for (; i < shots.size() - 1 && left > shots.get(i).seconds(); i++) left -= shots.get(i).seconds();
            Net.Shot s = shots.get(i);
            if (i != shotIndex) {
                // A shot across the world: a real cut (the blink hides it), arriving already moving.
                Want next = want(s, 0);
                if (pos.distanceTo(next.pos()) > FAR) fly(pos, look, next.pos(), t);
                shotIndex = i;
            }
            Want wnt = want(s, left / Math.max(0.01f, s.seconds()));
            tp = clear(wnt.pos(), wnt.look());
            tl = wnt.look();
            tf = wnt.fov() > 0 ? wnt.fov() : vanillaFov;
            tr = wnt.roll();
            // Easing out of your eyes takes its time; once moving it follows the shot closely.
            w = MathHelper.lerp(MathHelper.clamp(t / 0.9f, 0, 1), 4.2, 7.5);
            if (flyStart >= 0 && t - flyStart < flyDur) {
                // In flight: up and over on a curve, the lens opening with the speed, the gaze
                // turning toward what's ahead a beat before arriving.
                double k = ease3((t - flyStart) / flyDur);
                Vec3d end = wnt.pos();
                double d = flyFrom.distanceTo(end);
                Vec3d mid = flyFrom.add(end).multiply(0.5).add(0, Math.max(14, d * 0.32), 0);
                double u = 1 - k;
                tp = flyFrom.multiply(u * u).add(mid.multiply(2 * u * k)).add(end.multiply(k * k));
                double lk = ease3(Math.min(1, (t - flyStart) / (flyDur * 0.8)));
                tl = flyLookFrom.lerp(wnt.look(), lk);
                tf += (float) (16 * Math.sin(Math.PI * k));
                tr += (float) (6 * Math.sin(Math.PI * k));
                w = 16;
            }
        } else {
            if (homeAt < 0) homeAt = t;
            if (!blendOut) {
                // Cutting away somewhere else: hold the last frame while it closes to black.
                Want end = want(shots.get(shots.size() - 1), 1);
                tp = end.pos();
                tl = end.look();
                tf = end.fov() > 0 ? end.fov() : vanillaFov;
                tr = end.roll();
                w = 6;
            } else {
                // Home: your own eyes, wherever they are now, looking where you look.
                tp = mc.player.getCameraPosVec(td);
                tl = tp.add(mc.player.getRotationVec(td).multiply(10));
                tf = vanillaFov;
                tr = 0;
                w = MathHelper.lerp(MathHelper.clamp((t - homeAt) / 0.6f, 0, 1), 5.0, 9.0);
            }
        }
        // Integrate in small steps so a long frame can't overshoot.
        int n = Math.max(1, (int) Math.ceil(dt / (1 / 120.0)));
        double h = dt / n;
        for (int k = 0; k < n; k++) {
            Vec3d[] a = spring(pos, vel, tp, w, h);
            pos = a[0];
            vel = a[1];
            Vec3d[] b = spring(look, lookVel, tl, w * 1.15, h);
            look = b[0];
            lookVel = b[1];
            float[] c = spring(fovNow, fovVel, tf, w, h);
            fovNow = c[0];
            fovVel = c[1];
            float[] d = spring(rollNow, rollVel, tr, w, h);
            rollNow = d[0];
            rollVel = d[1];
        }
        Vec3d d = look.subtract(pos);
        double hz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, hz) * MathHelper.DEGREES_PER_RADIAN);
        // A held camera: the faintest drift, gone as it settles back into your eyes.
        float hand = home() ? (float) MathHelper.clamp(1 - (t - homeAt) / 0.4f, 0, 1) : (float) MathHelper.clamp(t / 0.8f, 0, 1);
        yaw += hand * (float) (0.22 * Math.sin(t * 0.83) + 0.11 * Math.sin(t * 2.17 + 1.3));
        pitch += hand * (float) (0.16 * Math.sin(t * 1.21 + 0.4) + 0.07 * Math.sin(t * 2.9));
        out = new Cam(pos.x, pos.y, pos.z, yaw, pitch);
    }

    /** Where the camera is right now, or null when no cutscene is playing. */
    public static Cam camera() {
        return camera(1f);
    }

    public static Cam camera(float td) {
        if (!active || shots.isEmpty() || MinecraftClient.getInstance().player == null) return null;
        advance(td);
        return out;
    }

    /** The lens a cutscene wants, or 0. */
    public static float fov() {
        return active ? fovNow : 0;
    }

    /** Tilts the view for a shot's roll. */
    public static void roll(MatrixStack m) {
        if (active && Math.abs(rollNow) > 0.01f) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rollNow));
    }

    /** Settled back into your eyes: close enough that letting go can't be seen. */
    private static boolean landed(MinecraftClient mc) {
        if (out == null) return false;
        Vec3d eye = mc.player.getCameraPosVec(1f);
        float dy = Math.abs(MathHelper.wrapDegrees(out.yaw() - mc.player.getYaw())), dp = Math.abs(out.pitch() - mc.player.getPitch());
        return pos.distanceTo(eye) < 0.04 && dy < 0.6f && dp < 0.6f && Math.abs(fovNow - vanillaFov) < 0.4f && Math.abs(rollNow) < 0.2f;
    }

    /** Controls are held while it plays; it lets go once the camera is home. */
    public static void tick(MinecraftClient mc) {
        if (!active) {
            if (!queue.isEmpty() && mc.player != null && (Util.getMeasuringTimeMs() - endedAt) > 400) start(queue.poll());
            return;
        }
        float t = elapsed();
        boolean done = mc.player == null
            || home() && (blendOut ? landed(mc) || t - homeAt > HOME_MAX : t - homeAt > 0.32f);
        if (done) {
            active = false;
            out = null;
            closedBlack = !blendOut;
            endedAt = Util.getMeasuringTimeMs();
            com.pglol.aotrpg.client.HudHide.show("cutscene");
            return;
        }
        // Hold jump to skip.
        boolean holding = InputUtil.isKeyPressed(mc.getWindow().getHandle(), InputUtil.fromTranslationKey(mc.options.jumpKey.getBoundKeyTranslationKey()).getCode());
        if (holding && !skipped && t > 0.4f) {
            if (skipFrom == 0) skipFrom = Util.getMeasuringTimeMs();
            else if ((Util.getMeasuringTimeMs() - skipFrom) / 1000f > SKIP_HOLD) skipped = true;
        } else skipFrom = 0;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.attackKey.setPressed(false);
        mc.options.useKey.setPressed(false);
        // Stop walking, but never yank you to a halt in mid-air.
        if (mc.player.isOnGround()) mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
    }

    /** Where the next shot is far from the last: a real cut, hidden in a quick blink. */
    private static float cutDip(float t) {
        float at = 0, a = 0;
        for (int i = 0; i < shots.size() - 1; i++) {
            at += shots.get(i).seconds();
            if (want(shots.get(i), 1).pos().distanceTo(want(shots.get(i + 1), 0).pos()) <= FAR) continue;
            float d = Math.abs(t - at);
            if (d < 0.11f) a = Math.max(a, 1 - d / 0.11f);
        }
        return a;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        if (!active) {
            // After a cut away (a drop, a lift-off): open back up from black.
            if (closedBlack) {
                float since = (Util.getMeasuringTimeMs() - endedAt) / 1000f;
                if (since > 0.45f) closedBlack = false;
                else c.fill(0, 0, w, h, (int) (255 * (1 - since / 0.45f)) << 24);
            }
            glimpse(c, w, h);
            return;
        }
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 900);
        drawBars(c, w, h);
        c.getMatrices().pop();
    }

    /** The bars, and how far they're in (they leave as the camera heads home). */
    private static float barK(float t) {
        float in = (float) smooth(t / 0.6f);
        if (!home()) return in;
        return in * (float) smooth(1 - (t - homeAt) / 0.5f);
    }

    private static void drawBars(DrawContext c, int w, int h) {
        float t = elapsed();
        float k = barK(t);
        int bar = (int) (h * 0.085f * k);
        c.fill(0, 0, w, bar, 0xFF000000);
        c.fill(0, h - bar, w, h, 0xFF000000);
        // A soft darkening toward the frame's edges while the bars are in.
        int vig = (int) (70 * k);
        if (vig > 2) {
            for (int i = 0; i < 6; i++) {
                int a = vig * (6 - i) / 6 / 3;
                int e = i * 6;
                c.fill(0, bar + e, 6, h - bar - e, a << 24);
                c.fill(w - 6, bar + e, w, h - bar - e, a << 24);
            }
        }
        if (fade && t < 2.2f) {
            // Waking up: black, a slow blink, then the world.
            float a = t < 0.8f ? 1 : t < 1.2f ? 1 - (t - 0.8f) / 0.4f * 0.7f : t < 1.5f ? 0.3f + (t - 1.2f) / 0.3f * 0.5f : 0.8f * (1 - (t - 1.5f) / 0.7f);
            c.fill(0, 0, w, h, (int) (255 * MathHelper.clamp(a, 0, 1)) << 24);
        }
        // In flight: the edges darken and streak with speed.
        if (flyStart >= 0 && t - flyStart < flyDur && !home()) {
            float k = (float) Math.sin(Math.PI * MathHelper.clamp((t - flyStart) / flyDur, 0, 1));
            int va = (int) (90 * k);
            for (int i = 0; i < 8; i++) {
                int e = i * 5, a = va * (8 - i) / 8;
                c.fill(0, e, w, e + 5, a << 24);
                c.fill(0, h - e - 5, w, h - e, a << 24);
            }
            var rnd = new java.util.Random((long) (t * 30));
            for (int i = 0; i < 14; i++) {
                int ly = rnd.nextInt(h), lx = rnd.nextInt(w), len = 30 + rnd.nextInt(80);
                c.fill(lx, ly, Math.min(w, lx + len), ly + 1, ((int) (60 * k)) << 24 | 0xFFFFFF);
            }
        }
        // Cutting away somewhere else: close on black.
        if (!blendOut && home()) c.fill(0, 0, w, h, (int) (255 * MathHelper.clamp((t - homeAt) / 0.3f, 0, 1)) << 24);
        if (!title.isEmpty()) {
            float in = MathHelper.clamp((t - 0.45f) / 0.5f, 0, 1);
            float outK = home() ? MathHelper.clamp(1 - (t - homeAt) / 0.3f, 0, 1) : MathHelper.clamp((total - t) / 0.6f, 0, 1);
            card(c, h, bar, title, sub, color, in, outK);
        }
        // Skip hint, and how far the hold has got.
        if (t > 0.9f && !home()) {
            float hold = skipFrom == 0 ? 0 : MathHelper.clamp((Util.getMeasuringTimeMs() - skipFrom) / 1000f / SKIP_HOLD, 0, 1);
            int a = (int) (110 * MathHelper.clamp((t - 0.9f) / 0.5f, 0, 1));
            String key = MinecraftClient.getInstance().options.jumpKey.getBoundKeyLocalizedText().getString().toUpperCase();
            net.minecraft.text.Text hint = net.minecraft.text.Text.literal("HOLD " + key + " TO SKIP");
            int tw = (int) (MinecraftClient.getInstance().textRenderer.getWidth(hint) * 0.7f);
            int x = w - 20 - tw, y = h - bar - 16;
            Ui.text(c, hint, x, y, 0.7f, (a << 24) | 0xD8D2C4, false);
            if (hold > 0) c.fill(x, y + 8, x + (int) (tw * hold), y + 9, 0xD0000000 | (color & 0xFFFFFF));
        }
    }

    /** A small title low in the corner: a short rule, the name, a line under it. */
    private static void card(DrawContext c, int h, int bar, String ttl, String sb, int col, float in, float outK) {
        int a = (int) (255 * in * outK);
        if (a <= 8) return;
        float slide = (float) (1 - smooth(in));
        int x = 22 - (int) (slide * 14), y = h - bar - (sb.isEmpty() ? 26 : 36);
        int rule = (int) ((sb.isEmpty() ? 10 : 22) * smooth(in));
        c.fill(x, y + 2, x + 2, y + 2 + rule, (a << 24) | (col & 0xFFFFFF));
        Ui.text(c, Ui.heading(ttl.toUpperCase()), x + 8, y, 1.1f, (a << 24) | 0xF2EDE2, false);
        if (!sb.isEmpty()) {
            float sIn = MathHelper.clamp(in * 1.6f - 0.6f, 0, 1);
            int sa = (int) (a * 0.75f * sIn);
            if (sa > 8) Ui.text(c, net.minecraft.text.Text.literal(sb), x + 8, y + 14, 0.75f, (sa << 24) | 0xC8C2B4, false);
        }
    }

    /** The title card alone, while you keep playing. */
    private static void glimpse(DrawContext c, int w, int h) {
        if (glimpseAt == 0) return;
        float t = (Util.getMeasuringTimeMs() - glimpseAt) / 1000f;
        if (t > 3.6f) {
            glimpseAt = 0;
            return;
        }
        float in = MathHelper.clamp(t / 0.45f, 0, 1), outK = MathHelper.clamp((3.6f - t) / 0.6f, 0, 1);
        card(c, h, (int) (h * 0.3f), gTitle, gSub, gColor, in, outK);
    }
}
