package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.HashMap;
import java.util.Map;

/**
 * Every player's body, every frame, moving like a person rather than a puppet.
 *
 * Two parts. First a target pose worked out from how they're moving: a real gait (shorter strides
 * than Minecraft's stick legs, the knee folding through the swing, loading on contact), the hips
 * dropping exactly as far as the planted leg is angled so the feet stay down and the body bobs with
 * each step, the hips swaying over the planted foot, shoulders turning against the hips, leaning into
 * speeding up and back when stopping, banking into turns, side-steps when strafing, a steady head,
 * idle weight shifts and breathing, tucking in the air and sinking into a landing.
 *
 * Then every joint follows its target through a spring, each with its own stiffness and give: the core
 * is firm, arms and head looser, so arms trail the chest, the head settles a beat late, and everything
 * overshoots a little and settles. That's the fluid, alive feel.
 *
 * Worked out on each client for every player it sees, from their movement alone: nothing is sent.
 */
public final class Animator {
    private Animator() {}

    private static final int N = Bone.values().length;

    /** Spring stiffness (per second squared) and damping ratio for each bone. */
    private static final float[] STIFF = new float[N], DAMP = new float[N];

    static {
        set(Bone.ROOT, 120, 0.95f);
        set(Bone.CHEST, 140, 0.8f);
        set(Bone.HEAD, 200, 0.9f);
        set(Bone.RIGHT_ARM, 85, 0.55f);
        set(Bone.LEFT_ARM, 85, 0.55f);
        set(Bone.RIGHT_FOREARM, 70, 0.55f);
        set(Bone.LEFT_FOREARM, 70, 0.55f);
        set(Bone.RIGHT_LEG, 420, 0.85f);
        set(Bone.LEFT_LEG, 420, 0.85f);
        set(Bone.RIGHT_SHIN, 380, 0.8f);
        set(Bone.LEFT_SHIN, 380, 0.8f);
    }

    private static void set(Bone b, float k, float zeta) {
        STIFF[b.ordinal()] = k;
        DAMP[b.ordinal()] = zeta;
    }

    private static final float ROOT_K = 260, ROOT_Z = 0.7f;

    private static final class State {
        final float[][] ang = new float[N][3], vel = new float[N][3];
        final float[] off = new float[3], offVel = new float[3];
        final Pose shown = new Pose();
        Pose out = new Pose();
        long last = System.nanoTime();
        boolean wasGround = true, first = true;
        double fallSpeed;
        float land, run, fast, crouch, air, gaitW;
        // Movement, from position (works for other players too).
        float fwd, side, accFwd, accSide, lastYaw, yawRate;
    }

    private static final Map<Integer, State> STATES = new HashMap<>();

    public static Pose pose(PlayerEntity p, float td) {
        State s = STATES.computeIfAbsent(p.getId(), k -> new State());
        long now = System.nanoTime();
        float dt = MathHelper.clamp((now - s.last) / 1e9f, 0, 0.1f);
        s.last = now;
        if (STATES.size() > 256) {
            var w = MinecraftClient.getInstance().world;
            STATES.keySet().removeIf(id -> w == null || w.getEntityById(id) == null);
        }
        if (dt <= 0 && !s.first) return s.out;

        motion(p, td, s, dt);
        Pose want = target(p, td, s, dt);
        Pose overlay = PoseStore.of(p);
        if (overlay != null) add(want, overlay);

        if (s.first) {
            s.first = false;
            for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) s.ang[b.ordinal()][a] = want.get(b, a);
            System.arraycopy(want.offset, 0, s.off, 0, 3);
        }
        // Springs, in small steps so stiff ones stay stable.
        int steps = Math.max(1, (int) Math.ceil(dt / (1 / 240f)));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            for (Bone b : Bone.values()) {
                int bi = b.ordinal();
                float k = STIFF[bi], c = 2 * DAMP[bi] * (float) Math.sqrt(k);
                for (int a = 0; a < 3; a++) {
                    float acc = k * (want.get(b, a) - s.ang[bi][a]) - c * s.vel[bi][a];
                    s.vel[bi][a] += acc * h;
                    s.ang[bi][a] += s.vel[bi][a] * h;
                }
            }
            float rc = 2 * ROOT_Z * (float) Math.sqrt(ROOT_K);
            for (int a = 0; a < 3; a++) {
                float acc = ROOT_K * (want.offset[a] - s.off[a]) - rc * s.offVel[a];
                s.offVel[a] += acc * h;
                s.off[a] += s.offVel[a] * h;
            }
        }
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) s.shown.set(b, a, s.ang[b.ordinal()][a]);
        System.arraycopy(s.off, 0, s.shown.offset, 0, 3);
        s.shown.gait = want.gait;

        // The body faces where they look (Minecraft lets it drift towards the way they walk), so strafing
        // is a side-step and backing off is a backpedal. The head doesn't inherit any of the body's turning
        // (see Rig.neck), so it keeps Minecraft's own look exactly; nothing to undo, nothing to roll.
        Pose sh = s.shown;
        float turn = 0;
        if (want.gait) {
            float body = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
            float face = MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw);
            turn = MathHelper.wrapDegrees(face - body);
        }
        Pose out = sh.copy();
        out.set(Bone.ROOT, Pose.YAW, sh.get(Bone.ROOT, Pose.YAW) + turn);
        s.out = out;
        return out;
    }

    /**
     * How fast they're going forward and sideways relative to where they look, from the game's own
     * movement ticks (steady, unlike frame-to-frame positions), and how that's changing; how fast they turn.
     */
    private static void motion(PlayerEntity p, float td, State s, float dt) {
        float face = MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw);
        double vx = (p.getX() - p.prevX) * 20, vz = (p.getZ() - p.prevZ) * 20;
        double r = Math.toRadians(face);
        double fx = -Math.sin(r), fz = Math.cos(r);
        float fwd = (float) (vx * fx + vz * fz), side = (float) (vx * fz - vz * fx);
        float k = 1 - (float) Math.exp(-dt / 0.12f);
        float nf = s.fwd + (fwd - s.fwd) * k, ns = s.side + (side - s.side) * k;
        float ka = 1 - (float) Math.exp(-dt / 0.25f);
        if (dt > 0) {
            s.accFwd += ((nf - s.fwd) / dt - s.accFwd) * ka;
            s.accSide += ((ns - s.side) / dt - s.accSide) * ka;
        }
        s.fwd = nf;
        s.side = ns;
        float yr = MathHelper.wrapDegrees(p.headYaw - p.prevHeadYaw) * 20;
        s.yawRate += (yr - s.yawRate) * (1 - (float) Math.exp(-dt / 0.2f));
        s.lastYaw = face;
    }

    private static Pose target(PlayerEntity p, float td, State s, float dt) {
        Pose o = new Pose();
        float t = (p.age + td) / 20f;
        boolean ground = p.isOnGround();
        boolean special = p.hasVehicle() || p.isFallFlying() || p.isSwimming() || p.isInSwimmingPose() || p.getAbilities().flying;
        o.gait = !special;

        float limb = p.limbAnimator.getPos(td);
        float speed = MathHelper.clamp(p.limbAnimator.getSpeed(td), 0, 1.2f);
        s.gaitW = approach(s.gaitW, special ? 0 : Math.min(1, speed), dt * 6);
        float w = s.gaitW;
        // Fast walk (double-tap forward) and run (holding sprint) look different: see Move.
        float mvNow = (float) Math.sqrt(s.fwd * s.fwd + s.side * s.side);
        Move.Mode md = Move.of(p, mvNow);
        s.run = approach(s.run, md == Move.Mode.RUN && ground ? 1 : 0, dt * 4);
        s.fast = approach(s.fast, md == Move.Mode.FAST && ground ? 1 : 0, dt * 4);
        s.crouch = approach(s.crouch, p.isInSneakingPose() ? 1 : 0, dt * 7);
        s.air = approach(s.air, ground || special || p.isTouchingWater() ? 0 : 1, dt * 7);

        // Landing: the body sinks into it and comes back up, deeper after a long fall.
        double vy = p.getVelocity().y;
        if (!ground) s.fallSpeed = Math.max(s.fallSpeed, -vy);
        if (ground && !s.wasGround) {
            float hard = (float) MathHelper.clamp((s.fallSpeed - 0.2) / 0.9, 0, 1);
            s.land = Math.max(s.land, 0.3f + 0.7f * hard);
            s.fallSpeed = 0;
        }
        s.wasGround = ground;
        s.land = Math.max(0, s.land - dt * 2.4f);
        float land = s.land * (2 - s.land);

        float run = s.run, fast = s.fast;
        // How much of the motion is sideways or backwards (strafing, backpedalling).
        float mv = (float) Math.sqrt(s.fwd * s.fwd + s.side * s.side);
        float sideK = mv > 0.3f ? MathHelper.clamp(Math.abs(s.side) / mv, 0, 1) * Math.signum(s.side) : 0;
        float backK = mv > 0.3f ? MathHelper.clamp(-s.fwd / mv, 0, 1) : 0;

        // ---- Gait ----
        float phase = limb * 0.6662f;
        float c = MathHelper.cos(phase), sn = MathHelper.sin(phase);
        // Rough: no two steps quite the same when pushing it (a little more or less on each one).
        float rough = 1 + 0.16f * jitter(MathHelper.floor(phase / MathHelper.PI)) * (fast + run);
        float stride = (30 + 12 * fast + 24 * run) * rough * (1 - 0.75f * Math.abs(sideK)) * (1 - 0.35f * backK);
        // Legs: Minecraft's swing is taken out (gait), ours put in: right leg forward when c < 0.
        float rHip = c * stride * w, lHip = -c * stride * w;
        o.set(Bone.RIGHT_LEG, Pose.PITCH, rHip);
        o.set(Bone.LEFT_LEG, Pose.PITCH, lHip);
        // Side-steps: legs reach out sideways in turn.
        // Side-steps: the lead leg reaches out the way they're going, the other follows it in.
        float step = w * Math.abs(sideK) * 24;
        o.set(Bone.RIGHT_LEG, Pose.ROLL, sideK > 0 ? step * Math.max(0, sn) : -step * 0.35f * Math.max(0, sn));
        o.set(Bone.LEFT_LEG, Pose.ROLL, sideK < 0 ? -step * Math.max(0, -sn) : step * 0.35f * Math.max(0, -sn));
        // Knees: fold through the swing (more running), give a little on contact.
        float swingFold = 32 + 26 * fast + 64 * run;
        float rKnee = w * (Math.max(0, sn) * swingFold + (1 - Math.abs(sn)) * 9);
        float lKnee = w * (Math.max(0, -sn) * swingFold + (1 - Math.abs(sn)) * 9);
        // Arms. Walking and fast walking: a free swing opposite the legs, loose and wide when pushing it,
        // shoulders thrown into it. Running: the anime run, both arms swept straight back behind, loose
        // at the elbow, bouncing with each stride, a bit out from the body (delinquent, not textbook).
        float armAmp = (34 + 24 * fast) * rough * (1 - 0.6f * Math.abs(sideK));
        float rSwing = -c * armAmp * w, lSwing = c * armAmp * w;
        float back = 68 * rough;
        o.set(Bone.RIGHT_ARM, Pose.PITCH, MathHelper.lerp(run, rSwing, w * (back + c * 9)));
        o.set(Bone.LEFT_ARM, Pose.PITCH, MathHelper.lerp(run, lSwing, w * (back - c * 9)));
        o.set(Bone.RIGHT_ARM, Pose.ROLL, 5 + w * (2 + 9 * fast + 13 * run));
        o.set(Bone.LEFT_ARM, Pose.ROLL, -5 - w * (2 + 9 * fast + 13 * run));
        // Swept back, the arms turn so the palms face up and back.
        o.set(Bone.RIGHT_ARM, Pose.YAW, w * run * 12);
        o.set(Bone.LEFT_ARM, Pose.YAW, -w * run * 12);
        float rElbow = w * (6 + 12 * fast + Math.max(0, c) * (24 + 6 * fast));
        float lElbow = w * (6 + 12 * fast + Math.max(0, -c) * (24 + 6 * fast));
        float loose = w * (10 + 6 * Math.abs(sn));
        o.set(Bone.RIGHT_FOREARM, Pose.PITCH, MathHelper.lerp(run, rElbow, loose));
        o.set(Bone.LEFT_FOREARM, Pose.PITCH, MathHelper.lerp(run, lElbow, loose));

        // The body: drops as far as the planted leg is angled (feet stay down), bobbing twice a stride;
        // sways over the planted foot; shoulders turn against the hips.
        float planted = (float) Math.toRadians(Math.abs(c) * stride * w);
        float drop = 12 * (1 - MathHelper.cos(planted)) + w * (0.4f + 0.6f * fast + 0.9f * run) * (1 - Math.abs(sn) * 0.5f);
        o.offset[1] = drop;
        o.offset[0] = w * sn * (0.3f + 0.35f * fast + 0.15f * run);
        o.set(Bone.ROOT, Pose.ROLL, -w * sn * (0.8f + 1.8f * fast + 0.7f * run));
        o.set(Bone.CHEST, Pose.YAW, w * c * (6 + 10 * fast + 11 * run) * rough);
        o.set(Bone.CHEST, Pose.ROLL, w * sn * (1.2f + 2.8f * fast + 2.4f * run));
        // Pushing forward: shoulders hunched over it.
        o.set(Bone.CHEST, Pose.PITCH, w * (5 * fast + 16 * run));

        // Momentum: lean with speed and into speeding up, back when stopping or backpedalling; bank into turns.
        float lean = w * (1.5f + 5 * fast + 17 * run) - backK * w * 5 + MathHelper.clamp(s.accFwd * 0.7f, -5, 5);
        o.set(Bone.ROOT, Pose.PITCH, lean);
        // Strafing leans into the way they're going (more as they set off), turning banks into the turn.
        float bank = MathHelper.clamp(s.yawRate * 0.012f * w, -6, 6) + sideK * w * 5 + MathHelper.clamp(s.accSide * 0.4f, -3, 3);
        add(o, Bone.ROOT, Pose.ROLL, bank);

        // Idle: weight shifting from foot to foot, breathing that lifts the shoulders.
        float idle = 1 - w;
        float shift = MathHelper.sin(t * 0.55f);
        float breathe = MathHelper.sin(t * 1.7f);
        o.offset[0] += idle * shift * 0.5f;
        add(o, Bone.ROOT, Pose.ROLL, -idle * shift * 1.2f);
        add(o, Bone.CHEST, Pose.PITCH, breathe * 1.4f - idle * 1.5f);
        add(o, Bone.RIGHT_ARM, Pose.ROLL, idle * (3 + breathe));
        add(o, Bone.LEFT_ARM, Pose.ROLL, -idle * (3 + breathe));
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, idle * (10 + breathe * 2));
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, idle * (10 + breathe * 2));
        rKnee += idle * (5 + Math.max(0, shift) * 6);
        lKnee += idle * (5 + Math.max(0, -shift) * 6);

        // Crouch (sneaking): the Crouch pose, hips lowered so the feet stay down.
        if (s.crouch > 0) {
            for (Bone b : Bone.values()) {
                if (b == Bone.RIGHT_SHIN || b == Bone.LEFT_SHIN) continue;
                for (int ax = 0; ax < 3; ax++) add(o, b, ax, Presets.CROUCH.get(b, ax) * s.crouch);
            }
            rKnee += Presets.CROUCH.get(Bone.RIGHT_SHIN, Pose.PITCH) * s.crouch;
            lKnee += Presets.CROUCH.get(Bone.LEFT_SHIN, Pose.PITCH) * s.crouch;
            o.offset[1] += Presets.CROUCH_DROP * s.crouch;
        }

        // In the air: tuck going up, reach for the ground coming down, arms out for balance.
        float rising = (float) MathHelper.clamp(vy / 0.42, -1, 1);
        float up = Math.max(0, rising), down = Math.max(0, -rising);
        rKnee += s.air * (60 * up + 14 * down);
        lKnee += s.air * (28 * up + 20 * down);
        add(o, Bone.RIGHT_LEG, Pose.PITCH, s.air * (-34 * up + 6 * down));
        add(o, Bone.LEFT_LEG, Pose.PITCH, s.air * (8 * up - 10 * down));
        add(o, Bone.RIGHT_ARM, Pose.ROLL, s.air * (6 + 22 * down));
        add(o, Bone.LEFT_ARM, Pose.ROLL, -s.air * (6 + 22 * down));
        add(o, Bone.RIGHT_ARM, Pose.PITCH, -s.air * 18 * up);
        add(o, Bone.LEFT_ARM, Pose.PITCH, -s.air * 12 * up);
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, s.air * 30);
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, s.air * 30);
        add(o, Bone.CHEST, Pose.PITCH, s.air * (6 * up - 4 * down));

        // Landing: sink, with the legs bending just enough to keep the feet down (simple leg IK).
        float sink = land * 4.5f;
        o.offset[1] += sink;
        float a = (float) Math.toDegrees(Math.acos(MathHelper.clamp(1 - sink / 12f, -1, 1)));
        add(o, Bone.RIGHT_LEG, Pose.PITCH, -a);
        add(o, Bone.LEFT_LEG, Pose.PITCH, -a);
        rKnee += 2 * a;
        lKnee += 2 * a;
        add(o, Bone.CHEST, Pose.PITCH, land * 22);
        add(o, Bone.RIGHT_ARM, Pose.PITCH, -land * 18);
        add(o, Bone.LEFT_ARM, Pose.PITCH, -land * 18);
        add(o, Bone.RIGHT_ARM, Pose.ROLL, land * 12);
        add(o, Bone.LEFT_ARM, Pose.ROLL, -land * 12);

        o.set(Bone.RIGHT_SHIN, Pose.PITCH, rKnee);
        o.set(Bone.LEFT_SHIN, Pose.PITCH, lKnee);

        return o;
    }

    /** A steady random number in -1..1 for each step. */
    private static float jitter(int step) {
        int x = step * 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return ((x & 0xFFFF) / 32767.5f) - 1;
    }

    private static float approach(float v, float target, float step) {
        return v < target ? Math.min(target, v + step) : Math.max(target, v - step);
    }

    private static void add(Pose p, Bone b, int axis, float deg) {
        p.set(b, axis, p.get(b, axis) + deg);
    }

    private static void add(Pose into, Pose from) {
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) add(into, b, a, from.get(b, a));
        for (int k = 0; k < 3; k++) into.offset[k] += from.offset[k];
    }
}
