package com.pglol.aotrpg.client;

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
 * A finisher as a scene everyone nearby sees: the dash in, the blade raised high in both hands,
 * the cut, and the follow-through on one knee while the foe stands a beat too long and falls.
 * The two in it get a camera of their own circling the moment. Timings match the server's
 * (in ticks): dash 0-5, wind-up to 14, the cut 14-17, the kill at 24, over at 34.
 */
public final class FinishScene {
    private FinishScene() {}

    public static final float DASH = 5, CUT = 14, HIT = 17, KILL = 24, END = 34;

    private record Scene(int by, int on, long at) { }

    /** Each one in a scene, by entity id. */
    private static final Map<Integer, Scene> scenes = new HashMap<>();

    public static void start(int by, int on) {
        Scene s = new Scene(by, on, Util.getMeasuringTimeMs());
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

    // ------------------------------------------------------------------ bodies

    /** The whole body: the lunge, the drop to one knee; the foe's sway. True if it took over. */
    public static boolean transforms(LivingEntity e, MatrixStack ms) {
        Scene s = of(e.getId());
        if (s == null) return false;
        float t = t(s), h = e.getHeight() * 0.5f;
        if (e.getId() == s.by()) {
            float lunge = t < DASH ? 1 : 1 - seg(t, DASH, DASH + 3);
            float set = seg(t, DASH, CUT) * (1 - seg(t, CUT, HIT));
            float kneel = seg(t, CUT + 1, HIT + 2) * (1 - seg(t, END - 4, END));
            ms.translate(0, -0.18f * set - 0.42f * kneel, 0);
            ms.translate(0, h, 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-30 * lunge + 8 * set - 18 * kneel));
            // The cut twists the whole body round through it.
            float twist = MathHelper.lerp(seg(t, CUT, HIT), 0, 1);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(25 * set - 35 * twist * (1 - seg(t, END - 6, END))));
            ms.translate(0, -h, 0);
        } else if (e.deathTime <= 0) {
            // Reeling at the dash, then held up there, swaying, until it lets go.
            float reel = seg(t, 0, DASH) * (1 - seg(t, CUT, HIT));
            float struck = seg(t, CUT + 1, HIT) * (1 - seg(t, HIT + 3, KILL));
            float sway = MathHelper.sin(t * 0.7f) * 3 * seg(t, HIT, KILL);
            ms.translate(0, -0.12f * reel, 0);
            ms.translate(0, h, 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(14 * reel + 22 * struck + sway));
            ms.translate(0, -h, 0);
        }
        return true;
    }

    /** The limbs. True if it took over. */
    public static boolean pose(BipedEntityModel<?> m, LivingEntity e) {
        Scene s = of(e.getId());
        if (s == null) return false;
        float t = t(s);
        if (e.getId() == s.by()) {
            // The dash: blade swept back low, body flat out, a long stride.
            float d = t < DASH ? 1 : 1 - seg(t, DASH, DASH + 3);
            // Wind-up: both hands on the hilt, high over the head.
            float up = seg(t, DASH, CUT - 2) * (1 - seg(t, CUT, HIT));
            // The cut: down and across, fast.
            float cut = seg(t, CUT, HIT);
            float out = seg(t, END - 5, END);
            float ra = -2.95f * up + (0.55f * cut) * (1 - out) + 0.9f * d * (1 - up);
            float la = -2.7f * up + (0.35f * cut) * (1 - out) - 0.5f * d * (1 - up);
            m.rightArm.pitch = ra;
            m.leftArm.pitch = la;
            m.rightArm.yaw = -0.35f * up + 0.6f * cut * (1 - out);
            m.leftArm.yaw = 0.45f * up + 0.2f * cut * (1 - out);
            m.rightArm.roll = 0.15f * d - 0.4f * cut * (1 - out);
            m.leftArm.roll = -0.15f * d + 0.25f * cut * (1 - out);
            // Legs: the stride, a wide stance under the wind-up, one knee down after.
            float kneel = seg(t, CUT + 1, HIT + 2) * (1 - out);
            m.rightLeg.pitch = -0.9f * d + 0.25f * up * (1 - kneel) - 1.45f * kneel;
            m.leftLeg.pitch = 0.6f * d - 0.5f * up * (1 - kneel) + 0.25f * kneel;
            m.rightLeg.roll = 0.12f * up;
            m.leftLeg.roll = -0.12f * up;
            m.rightLeg.yaw = m.leftLeg.yaw = 0;
            m.head.pitch = -0.25f * up + 0.35f * kneel - 0.3f * d;
            m.body.pitch = 0.1f * kneel;
        } else {
            if (e.deathTime > 0) return false;
            // Arms limp, head thrown back at the cut, then hanging.
            float struck = seg(t, CUT + 1, HIT);
            float hang = seg(t, HIT + 2, KILL);
            m.rightArm.pitch = -0.2f - 0.4f * struck + 0.3f * hang;
            m.leftArm.pitch = -0.15f - 0.45f * struck + 0.3f * hang;
            m.rightArm.roll = 0.25f + 0.5f * struck * (1 - hang);
            m.leftArm.roll = -0.25f - 0.5f * struck * (1 - hang);
            m.rightArm.yaw = m.leftArm.yaw = 0;
            m.rightLeg.pitch = 0.15f * hang;
            m.leftLeg.pitch = -0.2f * hang;
            m.head.pitch = -0.6f * struck * (1 - hang) + 0.55f * hang;
        }
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

    /** The scene's own camera: low and to the side, circling slowly, pushing in for the cut. */
    public static com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return null;
        Scene s = of(mc.player.getId());
        if (s == null) return null;
        Entity a = mc.world.getEntityById(s.by()), b = mc.world.getEntityById(s.on());
        if (a == null || b == null) return null;
        float t = t(s);
        Vec3d pa = a.getLerpedPos(td), pb = b.getLerpedPos(td);
        Vec3d mid = pa.add(pb).multiply(0.5).add(0, 1.0, 0);
        Vec3d along = pb.subtract(pa).multiply(1, 0, 1);
        if (along.lengthSquared() < 1e-4) along = Vec3d.fromPolar(0, a.getYaw());
        along = along.normalize();
        double base = Math.atan2(along.z, along.x);
        // A side-on shot that swings round behind the one striking as the cut lands.
        double ang = base + Math.PI / 2 + Math.toRadians(-20 + 55 * seg(t, 0, END));
        float push = seg(t, DASH, CUT) * (1 - seg(t, HIT, KILL));
        double dist = 4.6 - 1.4 * push + 0.8 * seg(t, KILL, END);
        double height = 0.35 - 0.45 * push + 0.5 * seg(t, KILL, END);
        Vec3d want = mid.add(Math.cos(ang) * dist, height, Math.sin(ang) * dist);
        // Kept out of walls.
        var hit = mc.world.raycast(new RaycastContext(mid, want, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) want = mid.lerp(hit.getPos(), 0.85);
        // A shudder as the blade lands.
        float shake = (1 - seg(t, HIT, HIT + 4)) * seg(t, CUT, HIT);
        Vec3d look = mid.subtract(want);
        float yaw = (float) (Math.toDegrees(Math.atan2(look.z, look.x)) - 90) + MathHelper.sin(t * 9) * 1.5f * shake;
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z))) + MathHelper.cos(t * 11) * 1.5f * shake;
        return new com.pglol.aotrpg.client.story.CutscenePlayer.Cam(want.x, want.y, want.z, yaw, pitch);
    }
}
