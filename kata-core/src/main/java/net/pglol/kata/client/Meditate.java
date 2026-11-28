package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.HashMap;
import java.util.Map;

/**
 * Meditation, seen: the fighter floats a hand's breadth off the ground cross-legged, hands on their
 * knees, breathing, a faint essence drifting up round them (brighter once they're untouchable). For the
 * one meditating, the camera eases round to frame them on the left of the screen and drifts slowly
 * there while the menu is open (see MeditationScreen).
 */
public final class Meditate {
    private Meditate() {}

    /** Who's meditating, as the server says (1 settling, 2 untouchable). */
    private static final Map<Integer, Integer> STATE = new HashMap<>();
    /** How far into the pose each body is (0..1, eased in and out). */
    private static final Map<Integer, Float> WEIGHT = new HashMap<>();

    /** The menu's open here (before the server has answered). */
    static boolean local;
    private static float yaw0, camW;
    private static long openedAt;

    static void heard(int entity, int state) {
        if (state == 0) STATE.remove(entity);
        else STATE.put(entity, state);
        if (STATE.size() > 256) STATE.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        // The server ended it (moved, struck, hit while settling): the menu closes.
        if (state == 0 && mc.player != null && entity == mc.player.getId() && mc.currentScreen instanceof MeditationScreen s) s.close();
    }

    public static int state(int entity) {
        return STATE.getOrDefault(entity, 0);
    }

    static void open(PlayerEntity p) {
        local = true;
        yaw0 = p.getYaw();
        openedAt = System.nanoTime();
        STATE.putIfAbsent(p.getId(), 1);
    }

    static void close(PlayerEntity p) {
        local = false;
        if (p != null) STATE.remove(p.getId());
    }

    private static boolean on(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return STATE.containsKey(p.getId()) || (local && mc.player != null && p.getId() == mc.player.getId());
    }

    /** The pose, over whatever the body was doing (eased in over half a second, out faster). */
    static void pose(PlayerEntity p, float dt, Pose out) {
        float w = WEIGHT.getOrDefault(p.getId(), 0f);
        boolean on = on(p);
        w = MathHelper.clamp(w + (on ? dt / 0.6f : -dt / 0.3f), 0, 1);
        if (w <= 0) {
            WEIGHT.remove(p.getId());
            return;
        }
        WEIGHT.put(p.getId(), w);
        if (WEIGHT.size() > 256) WEIGHT.clear();
        float e = w * w * (3 - 2 * w);
        float t = (System.nanoTime() % 62_831_853_000L) / 1e9f;
        float breath = MathHelper.sin(t * 1.4f);
        Pose l = out.copy();
        // Cross-legged: thighs out forward and wide, shins folded in under the other knee.
        l.set(Bone.RIGHT_LEG, -82, 14, 34).set(Bone.LEFT_LEG, -82, -14, -34);
        l.set(Bone.RIGHT_SHIN, 132, 0, 0).set(Bone.LEFT_SHIN, 132, 0, 0);
        // Hands resting on the knees, palms up.
        l.set(Bone.RIGHT_ARM, -34, -6, 12).set(Bone.LEFT_ARM, -34, 6, -12);
        l.set(Bone.RIGHT_FOREARM, 38, 0, 0).set(Bone.LEFT_FOREARM, 38, 0, 0);
        l.set(Bone.CHEST, -2 + breath * 1.5f, 0, 0).set(Bone.HEAD, 8 - breath, 0, 0);
        l.set(Bone.ROOT, 0, out.get(Bone.ROOT, Pose.YAW), 0);
        l.offset[0] = 0;
        l.offset[2] = 0;
        // Sitting would put the hips on the floor (12 down); it floats a little above that, bobbing.
        l.offset[1] = 8 - 1.2f * MathHelper.sin(t * 0.9f);
        l.gait = true;
        Pose blended = Pose.lerp(out, l, e);
        out.keep(blended, 0); // copy the blend into out
        out.gait = blended.gait;
    }

    /** Every tick: the essence round anyone meditating. */
    static void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null || STATE.isEmpty()) return;
        var r = mc.world.random;
        for (var en : STATE.entrySet()) {
            var e = mc.world.getEntityById(en.getKey());
            if (e == null || e.squaredDistanceTo(mc.player) > 48 * 48) continue;
            boolean full = en.getValue() == 2;
            if (r.nextInt(full ? 2 : 5) != 0) continue;
            double a = r.nextDouble() * Math.PI * 2, rad = 0.45 + r.nextDouble() * 0.35;
            Vec3d at = e.getPos().add(Math.cos(a) * rad, 0.2 + r.nextDouble() * 1.2, Math.sin(a) * rad);
            mc.world.addParticle(full ? ParticleTypes.END_ROD : ParticleTypes.WAX_OFF, at.x, at.y, at.z, -Math.cos(a) * 0.004, 0.012 + r.nextDouble() * 0.01, -Math.sin(a) * 0.004);
        }
    }

    /**
     * The camera while the menu's open: out in front of the fighter and to one side, looking past them so
     * they sit on the left of the screen, drifting slowly round. Blended in from wherever the camera was.
     */
    static KataCam.Cam camera(KataCam.Cam was, float td, float dt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        camW = MathHelper.clamp(camW + (local ? dt / 0.7f : -dt / 0.4f), 0, 1);
        if (camW <= 0 || mc.player == null) return was;
        var p = mc.player;
        float secs = (System.nanoTime() - openedAt) / 1e9f;
        float orbit = yaw0 + 205 + 12 * MathHelper.sin(secs * 0.12f);
        Vec3d chest = p.getLerpedPos(td).add(0, 0.95, 0);
        Vec3d out = Vec3d.fromPolar(0, orbit);
        Vec3d pos = chest.add(out.multiply(-3.1)).add(0, 0.35, 0);
        // Looking at a spot to the camera's right of them, so they stand on the left third.
        Vec3d look = chest.subtract(pos);
        Vec3d right = new Vec3d(-look.z, 0, look.x).normalize();
        Vec3d at = chest.add(right.multiply(-1.15)).add(0, -0.1, 0);
        Vec3d d = at.subtract(pos);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        float e = camW * camW * (3 - 2 * camW);
        if (was == null) {
            Vec3d eye = p.getCameraPosVec(td);
            was = new KataCam.Cam(eye.x, eye.y, eye.z, p.getYaw(td), p.getPitch(td));
        }
        return new KataCam.Cam(MathHelper.lerp(e, was.x(), pos.x), MathHelper.lerp(e, was.y(), pos.y), MathHelper.lerp(e, was.z(), pos.z),
            MathHelper.lerpAngleDegrees(e, was.yaw(), yaw), MathHelper.lerp(e, was.pitch(), pitch));
    }
}
