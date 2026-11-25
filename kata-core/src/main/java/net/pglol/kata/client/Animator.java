package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.HashMap;
import java.util.Map;

/**
 * Every player's body, every frame: footwork worked out from how they're moving (idle breathing,
 * walking, running, crouching, jumping, falling and landing), with the pose editor's pose (yours) laid
 * on top, all eased so nothing snaps. Runs on each client for every player it can see, so everyone's
 * knees and elbows bend without sending anything over the network.
 */
public final class Animator {
    private Animator() {}

    private static final class State {
        final Pose shown = new Pose();
        long last = System.nanoTime();
        boolean wasGround = true;
        double fallSpeed;
        float land, landHeavy;
        float run, crouch, air;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    /** How quickly the shown pose catches up with the wanted one (per second). */
    private static final float EASE = 14f;

    public static Pose pose(PlayerEntity p, float td) {
        State s = STATES.computeIfAbsent(p.getId(), k -> new State());
        long now = System.nanoTime();
        float dt = MathHelper.clamp((now - s.last) / 1e9f, 0, 0.1f);
        s.last = now;
        if (STATES.size() > 256) STATES.keySet().removeIf(id -> MinecraftClient.getInstance().world == null || MinecraftClient.getInstance().world.getEntityById(id) == null);

        Pose want = locomotion(p, td, s, dt);
        // Your editor pose on top (only you have one for now).
        Pose overlay = PoseStore.of(p);
        if (overlay != null) add(want, overlay);

        float k = 1 - (float) Math.exp(-EASE * dt);
        Pose next = Pose.lerp(s.shown, want, k);
        copy(next, s.shown);
        return s.shown;
    }

    private static Pose locomotion(PlayerEntity p, float td, State s, float dt) {
        Pose o = new Pose();
        float t = (p.age + td) / 20f;
        float limb = p.limbAnimator.getPos(td);
        float speed = MathHelper.clamp(p.limbAnimator.getSpeed(td), 0, 1.5f);
        boolean ground = p.isOnGround();

        // Smoothed states, so switching between them flows.
        s.run = approach(s.run, p.isSprinting() && ground ? 1 : 0, dt * 5);
        s.crouch = approach(s.crouch, p.isInSneakingPose() ? 1 : 0, dt * 8);
        s.air = approach(s.air, ground || p.isTouchingWater() || p.getAbilities().flying ? 0 : 1, dt * 7);

        // Landing: a dip in the knees that settles, deeper after a long fall.
        double vy = p.getVelocity().y;
        if (!ground) s.fallSpeed = Math.max(s.fallSpeed, -vy);
        if (ground && !s.wasGround) {
            float hard = (float) MathHelper.clamp((s.fallSpeed - 0.25) / 0.8, 0, 1);
            s.land = Math.max(s.land, 0.35f + 0.65f * hard);
            s.fallSpeed = 0;
        }
        s.wasGround = ground;
        s.land = Math.max(0, s.land - dt * 2.2f);
        float land = s.land * s.land;

        // Idle: breathing, a soft knee, relaxed elbows.
        float breathe = MathHelper.sin(t * 1.6f);
        o.set(Bone.CHEST, breathe * 1.2f, 0, 0);
        o.set(Bone.RIGHT_FOREARM, 8 + breathe, 0, 0).set(Bone.LEFT_FOREARM, 8 + breathe, 0, 0);
        o.set(Bone.RIGHT_SHIN, 4, 0, 0).set(Bone.LEFT_SHIN, 4, 0, 0);

        // Walking and running: knees fold as each leg swings through, elbows bend, the chest leans
        // forward and counter-twists against the hips, the head steadies.
        float w = MathHelper.clamp(speed, 0, 1);
        float phase = limb * 0.6662f;
        float rLeg = MathHelper.sin(phase), lLeg = MathHelper.sin(phase + MathHelper.PI);
        float kneeAmp = 30 + 35 * s.run;
        add(o, Bone.RIGHT_SHIN, Pose.PITCH, w * (8 + kneeAmp * Math.max(0, rLeg)));
        add(o, Bone.LEFT_SHIN, Pose.PITCH, w * (8 + kneeAmp * Math.max(0, lLeg)));
        float elbow = 18 + 62 * s.run;
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, w * elbow);
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, w * elbow);
        add(o, Bone.CHEST, Pose.PITCH, w * (3 + 12 * s.run));
        add(o, Bone.CHEST, Pose.YAW, w * MathHelper.cos(phase) * (5 + 6 * s.run));
        add(o, Bone.HEAD, Pose.PITCH, -w * (2 + 8 * s.run));

        // Crouching: knees deep, elbows in (Minecraft already tips the body).
        add(o, Bone.RIGHT_SHIN, Pose.PITCH, s.crouch * 35);
        add(o, Bone.LEFT_SHIN, Pose.PITCH, s.crouch * 35);
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, s.crouch * 25);
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, s.crouch * 25);

        // In the air: tucked going up, reaching down coming down.
        float rising = (float) MathHelper.clamp(vy / 0.42, -1, 1);
        float up = Math.max(0, rising), down = Math.max(0, -rising);
        add(o, Bone.RIGHT_SHIN, Pose.PITCH, s.air * (45 * up + 10 * down));
        add(o, Bone.LEFT_SHIN, Pose.PITCH, s.air * (25 * up + 15 * down));
        add(o, Bone.RIGHT_LEG, Pose.PITCH, s.air * (-25 * up + 5 * down));
        add(o, Bone.LEFT_LEG, Pose.PITCH, s.air * (5 * up - 8 * down));
        add(o, Bone.RIGHT_ARM, Pose.ROLL, s.air * 18 * down);
        add(o, Bone.LEFT_ARM, Pose.ROLL, -s.air * 18 * down);
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, s.air * 25);
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, s.air * 25);

        // Landing dip.
        add(o, Bone.RIGHT_SHIN, Pose.PITCH, land * 70);
        add(o, Bone.LEFT_SHIN, Pose.PITCH, land * 70);
        add(o, Bone.RIGHT_LEG, Pose.PITCH, -land * 35);
        add(o, Bone.LEFT_LEG, Pose.PITCH, -land * 35);
        add(o, Bone.CHEST, Pose.PITCH, land * 25);
        add(o, Bone.RIGHT_ARM, Pose.PITCH, -land * 20);
        add(o, Bone.LEFT_ARM, Pose.PITCH, -land * 20);
        return o;
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    private static void add(Pose p, Bone b, int axis, float deg) {
        p.set(b, axis, p.get(b, axis) + deg);
    }

    private static void add(Pose into, Pose from) {
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) add(into, b, a, from.get(b, a));
    }

    private static void copy(Pose from, Pose to) {
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) to.set(b, a, from.get(b, a));
    }
}
