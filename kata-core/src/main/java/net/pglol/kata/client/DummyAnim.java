package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Dummy;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Path;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.HashMap;
import java.util.Map;

/**
 * How a training dummy's body moves, from what the server says it's doing:
 *  - squared up when someone's close, a tight guard when it blocks
 *  - its jabs, straights and kicks
 *  - jolted by a block, snapping a parry, catching a kick, reeling when staggered
 *  - knocked down, a real ragdoll that gets back up
 * Springs smooth everything so it moves like a body.
 */
public final class DummyAnim {
    private DummyAnim() {}

    private static final class State {
        final float[][] ang = new float[Bone.values().length][3], vel = new float[Bone.values().length][3];
        long last;
        boolean ragdoll, wasDown, reported;
        int knocks;
        Pose shown = new Pose();
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    public static Pose pose(Dummy d, float td) {
        State s = STATES.computeIfAbsent(d.getId(), k -> new State());
        if (STATES.size() > 128) STATES.clear();
        long now = System.nanoTime();
        float dt = s.last == 0 ? 0 : MathHelper.clamp((now - s.last) / 1e9f, 0, 0.1f);
        s.last = now;
        float by = MathHelper.lerpAngleDegrees(td, d.prevBodyYaw, d.bodyYaw);

        // Knocked down: limp, then up again.
        boolean fresh = d.down() > 0 && (!s.wasDown || d.knocks() != s.knocks);
        s.wasDown = d.down() > 0;
        if (fresh && !s.ragdoll) {
            s.ragdoll = true;
            Ragdoll.start(d, s.shown, by, d.getVelocity().multiply(20));
            s.knocks = d.knocks();
            s.reported = false;
        }
        if (s.ragdoll) {
            Ragdoll r = Ragdoll.of(d);
            if (r != null && d.knocks() != s.knocks) r.impulse(d.getVelocity()); // hit again while down
            s.knocks = d.knocks();
            if (r != null && !r.finished()) {
                r.step(d.getWorld(), dt);
                Pose limp = r.pose(d, d.getLerpedPos(td), by, td);
                if (r.resting() && !s.reported) {
                    // Came to rest: tell the server where the body is, so it gets up there.
                    s.reported = true;
                    Vec3d off = r.pelvis().subtract(d.getPos()).add(0, -0.7, 0);
                    if (net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(net.pglol.kata.KataNet.Fight.ID))
                        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new net.pglol.kata.KataNet.Fight(net.pglol.kata.KataNet.DUMMY_REST, d.getId(),
                            Fighting.packOffset(off.x, Math.max(-0.5, off.y), off.z)));
                }
                s.shown = r.resting() ? r.getUpPose(limp, r.getUp, 0, 0) : limp;
                return s.shown;
            }
            Ragdoll.stop(d);
            s.ragdoll = false;
            for (float[] v : s.vel) java.util.Arrays.fill(v, 0);
        }

        Pose want = target(d, td);
        // Springs (stiffer for arms in a strike).
        boolean striking = d.move() >= 0;
        int steps = Math.max(1, (int) Math.ceil(dt / (1 / 240f)));
        float h = dt / Math.max(1, steps);
        for (int i = 0; i < steps && dt > 0; i++) {
            for (Bone b : Bone.values()) {
                int bi = b.ordinal();
                float k = striking ? 900 : 220, c = 2 * 0.8f * (float) Math.sqrt(k);
                for (int a = 0; a < 3; a++) {
                    float acc = k * (want.get(b, a) - s.ang[bi][a]) - c * s.vel[bi][a];
                    s.vel[bi][a] += acc * h;
                    s.ang[bi][a] += s.vel[bi][a] * h;
                }
            }
        }
        Pose out = new Pose();
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) out.set(b, a, dt > 0 ? s.ang[b.ordinal()][a] : want.get(b, a));
        System.arraycopy(want.offset, 0, out.offset, 0, 3);
        s.shown = out;
        return out;
    }

    private static Pose target(Dummy d, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean near = mc.player != null && mc.player.squaredDistanceTo(d) < 6 * 6;
        Pose o;
        if (d.guarding()) {
            o = Presets.GUARD.copy();
            o.set(Bone.RIGHT_FOREARM, Pose.PITCH, 118).set(Bone.LEFT_FOREARM, Pose.PITCH, 122);
            o.set(Bone.RIGHT_ARM, Pose.PITCH, o.get(Bone.RIGHT_ARM, Pose.PITCH) - 14);
            o.set(Bone.LEFT_ARM, Pose.PITCH, o.get(Bone.LEFT_ARM, Pose.PITCH) - 14);
            o.set(Bone.CHEST, Pose.PITCH, o.get(Bone.CHEST, Pose.PITCH) + 8);
            switch (d.blockKind()) {
                // Meeting the strike: the arm on its side goes out to it, forearm up to take it.
                case 1 -> o.set(Bone.RIGHT_ARM, -75, -10, 38).set(Bone.RIGHT_FOREARM, 70, 0, 0).set(Bone.CHEST, 6, -10, 0);
                case 2 -> o.set(Bone.LEFT_ARM, -75, 10, -38).set(Bone.LEFT_FOREARM, 70, 0, 0).set(Bone.CHEST, 6, 10, 0);
                // Checking a kick: the knee comes up and out to take it on the shin.
                case 3 -> o.set(Bone.LEFT_LEG, -62, 0, -18).set(Bone.LEFT_SHIN, 85, 0, 0).set(Bone.RIGHT_SHIN, 12, 0, 0);
                default -> { }
            }
        } else if (near || d.move() >= 0) {
            o = Pose.lerp(new Pose(), Presets.GUARD, 0.65f);
            o.set(Bone.RIGHT_FOREARM, Pose.PITCH, 75).set(Bone.LEFT_FOREARM, Pose.PITCH, 80);
        } else {
            // At ease: weight on one leg, arms loose, a slow breath.
            float t = (d.age + td) / 20f;
            o = new Pose();
            o.set(Bone.CHEST, 2 + MathHelper.sin(t * 1.3f) * 1.2f, 0, 0);
            o.set(Bone.RIGHT_ARM, 0, 0, 4).set(Bone.LEFT_ARM, 0, 0, -4);
            o.set(Bone.RIGHT_FOREARM, 8, 0, 0).set(Bone.LEFT_FOREARM, 8, 0, 0);
            o.set(Bone.LEFT_LEG, -4, 0, -3).set(Bone.LEFT_SHIN, 8, 0, 0);
            return o;
        }

        // Its strike.
        int id = d.move();
        if (id >= 0 && id < Fighting.MOVES.length) {
            Move m = Fighting.MOVES[id];
            float s = (d.moveAge() + td) / 20f;
            float c = m.contact(), k;
            if (s < c) k = (s / c) * (s / c);
            else if (s < c + 0.05f) k = 1;
            else k = 1 - MathHelper.clamp((s - c - 0.05f) / Math.max(0.05f, m.length() - c - 0.05f), 0, 1);
            int hit = m.hits() > 1 && m.interval() > 0 ? Math.min(m.hits() - 1, (int) (s / m.interval())) : 0;
            boolean right = Fighting.rightLimb(m, Fighting.TR, hit);
            float sign = right ? -1 : 1;
            if (m.leg()) {
                Bone leg = right ? Bone.RIGHT_LEG : Bone.LEFT_LEG, shin = right ? Bone.RIGHT_SHIN : Bone.LEFT_SHIN;
                boolean round = m.path() == Path.ROUNDHOUSE;
                o.set(leg, -85 * k, 0, (round ? -sign * 55 : 0) * k);
                o.set(shin, 60 * (1 - k) * k * 4 * 0.25f + 10, 0, 0);
                o.set(Bone.CHEST, -14 * k, sign * 20 * k, 0);
                o.set(Bone.ROOT, 0, sign * (round ? 40 : 8) * k, 0);
            } else {
                Bone arm = right ? Bone.RIGHT_ARM : Bone.LEFT_ARM, fore = right ? Bone.RIGHT_FOREARM : Bone.LEFT_FOREARM;
                o.set(arm, MathHelper.lerp(k, o.get(arm, Pose.PITCH), -88), MathHelper.lerp(k, o.get(arm, Pose.YAW), -sign * 6), 0);
                o.set(fore, MathHelper.lerp(k, o.get(fore, Pose.PITCH), 5), 0, 0);
                o.set(Bone.CHEST, 5 * k, sign * (m.lead() ? 12 : 28) * k, 0);
                o.offset[2] = -2 * k;
            }
        }

        // Reactions.
        int ra = d.reactionAge();
        float rk = ra < 3 ? 1 : MathHelper.clamp(1 - (ra - 3 + td) / 8f, 0, 1);
        if (rk > 0) {
            switch (d.reaction()) {
                case Dummy.BLOCK_R, Dummy.BLOCK_L -> {
                    float side = d.reaction() == Dummy.BLOCK_R ? 1 : -1;
                    o.set(Bone.CHEST, o.get(Bone.CHEST, Pose.PITCH) - 14 * rk, o.get(Bone.CHEST, Pose.YAW) - 16 * rk * side, 6 * rk * side);
                    o.set(Bone.HEAD, -10 * rk, 0, 0);
                }
                case Dummy.PARRY -> o.set(Bone.LEFT_ARM, -70 * rk, 0, -55 * rk).set(Bone.CHEST, 4, 18 * rk, 0);
                case Dummy.CATCH -> o.set(Bone.RIGHT_ARM, -55 - 60 * (1 - rk), 10, 0).set(Bone.LEFT_ARM, -55 - 60 * (1 - rk), -10, 0)
                    .set(Bone.RIGHT_FOREARM, 35, 0, 0).set(Bone.LEFT_FOREARM, 35, 0, 0);
                default -> { }
            }
        }
        if (d.staggered() > 0) {
            o.set(Bone.CHEST, -16, 0, 0).set(Bone.HEAD, -20, 0, 0);
            o.set(Bone.RIGHT_ARM, -25, 0, 38).set(Bone.LEFT_ARM, -25, 0, -38);
            o.set(Bone.RIGHT_FOREARM, 25, 0, 0).set(Bone.LEFT_FOREARM, 25, 0, 0);
            o.offset[2] = 1.5f;
        }
        return o;
    }

    /** The world-space spot a dummy's ragdoll pelvis is at (for following it), or null. */
    static Vec3d pelvis(Dummy d) {
        Ragdoll r = Ragdoll.of(d);
        return r == null ? null : r.pelvis();
    }
}
