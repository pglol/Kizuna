package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * The third-person camera (it replaces Minecraft's): over one shoulder, so you and whoever you're
 * fighting are both in view. Up in third person (behind) and whenever you're locked on; the swap key
 * moves it to the other shoulder. It rides a critically damped spring (it never snaps), trails your
 * speed a little, pulls in off walls instead of clipping, and looks at exactly what you're aiming at
 * so the middle of the screen is where your strike goes. (The same camera as the AoT mod's.)
 */
public final class KataCam {
    private KataCam() {}

    public record Cam(double x, double y, double z, float yaw, float pitch) { }

    /** The player's settings (the Camera screen), kept in config/kizuna/kata_camera.json. */
    public static final class Settings {
        public boolean thirdPerson = true, onLockOn = true;
        public float distance = 3.2f, side = 0.75f, height = 0.2f;
        /** 0 snappy .. 1 floaty. */
        public float smoothing = 0.45f;
        /** How far it trails your speed (0 none .. 1 a lot). */
        public float lag = 0.45f;
    }

    public static Settings cfg = new Settings();
    private static boolean loaded;

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("kizuna").resolve("kata_camera.json");
    }

    static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (java.nio.file.Files.exists(file())) {
                Settings s = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(file()), Settings.class);
                if (s != null) cfg = s;
            }
        } catch (Exception ignored) { }
    }

    public static void save() {
        try {
            java.nio.file.Files.createDirectories(file().getParent());
            java.nio.file.Files.writeString(file(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(cfg));
        } catch (Exception ignored) { }
    }

    private static float sideNow = 1, sideWant = 1, weight;
    private static Vec3d offset, offsetVel = Vec3d.ZERO, velNow;
    private static double aimDist;
    private static long lastFrame;

    private static boolean want(MinecraftClient mc) {
        if (mc.player == null || mc.player.isSpectator() || mc.player.isSleeping()) return false;
        load();
        Perspective per = mc.options.getPerspective();
        if (per == Perspective.THIRD_PERSON_FRONT) return false;
        return (cfg.thirdPerson && per == Perspective.THIRD_PERSON_BACK) || (cfg.onLockOn && Fight.locked());
    }

    public static boolean active() {
        return weight > 0.001f;
    }

    static void tick(MinecraftClient mc) {
        if (KataClient.shoulderKey == null) return;
        while (KataClient.shoulderKey.wasPressed()) if (active()) sideWant = -sideWant;
    }

    /** This frame's camera, or null to leave Minecraft's. */
    /** The camera this frame: the shoulder camera, or the meditation framing over it while the menu's open. */
    public static Cam camera(float td) {
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : MathHelper.clamp((now - lastFrame) / 1000f, 0.001f, 0.1f);
        lastFrame = now;
        return Meditate.camera(shoulder(td, dt), td, dt);
    }

    private static Cam shoulder(float td, float dt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        weight = MathHelper.clamp(weight + (want(mc) ? dt : -dt) / 0.32f, 0, 1);
        if (weight <= 0.001f || mc.player == null || mc.world == null) {
            offset = null;
            return null;
        }
        var p = mc.player;
        Vec3d eye = p.getCameraPosVec(td);
        float yaw = p.getYaw(td), pitch = p.getPitch(td);
        Vec3d fwd = Vec3d.fromPolar(pitch, yaw);
        float yr = yaw * MathHelper.RADIANS_PER_DEGREE;
        Vec3d right = new Vec3d(-MathHelper.cos(yr), 0, -MathHelper.sin(yr));
        Vec3d up = right.crossProduct(fwd).normalize();
        sideNow += (sideWant - sideNow) * (1 - (float) Math.exp(-dt * 10));
        Vec3d want = up.multiply(cfg.height).add(right.multiply(cfg.side * sideNow)).subtract(fwd.multiply(cfg.distance));
        // Trailing your speed (smoothed, so a knock or a boost doesn't jerk the view).
        Vec3d vel = p.getVelocity();
        velNow = velNow == null ? vel : velNow.lerp(vel, 1 - Math.exp(-dt * 8));
        Vec3d trail = velNow.multiply(-cfg.lag * 2.2);
        if (trail.length() > 1.4) trail = trail.normalize().multiply(1.4);
        want = want.add(trail);
        if (offset == null) {
            offset = want;
            offsetVel = Vec3d.ZERO;
        }
        float omega = MathHelper.lerp(cfg.smoothing, 28f, 7f);
        int steps = Math.max(1, (int) Math.ceil(dt * omega / 0.25f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            Vec3d acc = offset.subtract(want).multiply(-omega * omega).subtract(offsetVel.multiply(2 * omega));
            offsetVel = offsetVel.add(acc.multiply(h));
            offset = offset.add(offsetVel.multiply(h));
        }
        Vec3d off = offset.subtract(want);
        if (off.lengthSquared() > 4) {
            offset = want.add(off.normalize().multiply(2));
            offsetVel = offsetVel.multiply(0.3);
        }
        if (!Double.isFinite(offset.x) || !Double.isFinite(offset.y) || !Double.isFinite(offset.z)) {
            offset = want;
            offsetVel = Vec3d.ZERO;
        }
        // Off walls: pull in along the line from your head to the camera.
        Vec3d pivot = eye.add(up.multiply(Math.max(0, cfg.height) * 0.5));
        Vec3d target = eye.add(offset);
        var hit = mc.world.raycast(new RaycastContext(pivot, target, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() != HitResult.Type.MISS) {
            Vec3d d = target.subtract(pivot);
            double len = Math.max(0.01, d.length());
            double ok = Math.max(0.2, hit.getPos().distanceTo(pivot) - 0.25);
            target = pivot.add(d.multiply(ok / len));
        }
        float k = weight * weight * (3 - 2 * weight);
        Vec3d pos = eye.lerp(target, k);
        // Looking at exactly what you're aiming at (only the depth eases).
        var aimHit = p.raycast(64, td, false);
        double hitDist = aimHit.getType() == HitResult.Type.MISS ? 64 : aimHit.getPos().distanceTo(eye);
        if (mc.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult eh) hitDist = Math.min(hitDist, eh.getPos().distanceTo(eye));
        aimDist = aimDist <= 0 ? hitDist : aimDist + (hitDist - aimDist) * (1 - (float) Math.exp(-dt * (hitDist < aimDist ? 14 : 5)));
        Vec3d aim = eye.add(fwd.multiply(Math.max(3, aimDist)));
        Vec3d to = aim.subtract(pos);
        float camYaw = yaw, camPitch = pitch;
        if (to.lengthSquared() > 4) {
            double hz = Math.sqrt(to.x * to.x + to.z * to.z);
            camYaw = MathHelper.lerpAngleDegrees(k, yaw, (float) (MathHelper.atan2(to.z, to.x) * MathHelper.DEGREES_PER_RADIAN) - 90f);
            camPitch = MathHelper.lerp(k, pitch, (float) -(MathHelper.atan2(to.y, hz) * MathHelper.DEGREES_PER_RADIAN));
        }
        return new Cam(pos.x, pos.y, pos.z, camYaw, camPitch);
    }
}
