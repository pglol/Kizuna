package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    /** Arms tighten up when running, so they snap and fling instead of floating behind. */
    private static final boolean[] ARM = new boolean[N];

    static {
        for (Bone b : new Bone[] {Bone.RIGHT_ARM, Bone.LEFT_ARM, Bone.RIGHT_FOREARM, Bone.LEFT_FOREARM}) ARM[b.ordinal()] = true;
    }

    private static void set(Bone b, float k, float zeta) {
        STIFF[b.ordinal()] = k;
        DAMP[b.ordinal()] = zeta;
    }

    private static final float ROOT_K = 260, ROOT_Z = 0.7f;
    /** The upper body a guard takes over (the legs keep walking). */
    private static final Bone[] FIGHT_BONES = {Bone.CHEST, Bone.RIGHT_ARM, Bone.LEFT_ARM, Bone.RIGHT_FOREARM, Bone.LEFT_FOREARM};

    private static final class State {
        final float[][] ang = new float[N][3], vel = new float[N][3];
        final float[] off = new float[3], offVel = new float[3];
        final Pose shown = new Pose();
        Pose out = new Pose();
        long last = System.nanoTime();
        boolean wasGround = true, first = true;
        double fallSpeed;
        float land, run, fast, crouch, air, gaitW;
        // Hanging: the body as a pendulum under the hands (radians, radians per second).
        float dp, dr, vp, vr, hvR, accR;
        // ...and the legs swinging from the hips a beat behind it (relative to the body).
        float lp, lr, lvp, lvr;
        float sag, sagV;
        boolean wasHang;
        int lastHurt;
        boolean ragdolled;
        // Hands to a wall right in front: how much, and how far off it is.
        float wallW;
        double wallHit = 0.6;
        final float[] sideW = new float[2];
        final double[] sideHit = {0.5, 0.5};
        // Hand over hand: where each hand is planted, and a hand on its way to a new spot.
        Vec3d[] plant, stepFrom;
        float[] stepT = {-1, -1};
        boolean leadRight;
        /** This jump is backwards (both feet even, hands up); and how much of that is showing. */
        boolean backJump;
        float backW;
        // Movement, from position (works for other players too).
        float fwd, side, accFwd, accSide, lastYaw, yawRate;
        // Follow-through: the chest swings behind the body's changes of speed and turn, the arms behind
        // the chest, the legs (in the air) behind the body. {angle, speed} per axis.
        final float[] chP = new float[2], chR = new float[2], chY = new float[2];
        final float[] armP = new float[2], armR = new float[2], legP = new float[2], legR = new float[2];
        float ftFwd, ftSide, ftVy, ftYaw;
        boolean ftInit;
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

        // Ragdoll: the body is physics until it has got back up.
        Parkour.View rv = Parkour.view(p);
        if (rv.mode() == Parkour.Mode.RAGDOLL) {
            float by = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
            if (!s.ragdolled) {
                s.ragdolled = true;
                Ragdoll.start(p, s.out, by, Parkour.impact(p));
            }
            Ragdoll r = Ragdoll.of(p);
            if (r != null && !r.finished()) {
                r.step(p.getWorld(), dt);
                Pose shown;
                if (!r.resting()) shown = r.pose(p, p.getLerpedPos(td), by, td);
                else {
                    // Getting up from however it landed (pushing up, or sitting up), onto a knee, then up.
                    // It rises the way it lay and turns to where you face as it stands, in one smooth turn.
                    float frame = r.lieYaw + MathHelper.wrapDegrees(by - r.lieYaw) * r.turnShare();
                    Pose limp = r.pose(p, p.getLerpedPos(td), frame, td);
                    float lookP = MathHelper.lerp(td, p.prevPitch, p.getPitch());
                    float lookY = MathHelper.wrapDegrees(MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw) - frame);
                    shown = r.getUpPose(limp, r.getUp, lookP, lookY);
                    Ragdoll.turn(shown, MathHelper.wrapDegrees(frame - by));
                    Vec3d at = p.getLerpedPos(td);
                    Ragdoll.keepAbove(shown, at, by, at.y);
                }
                s.out = shown;
                s.first = true; // the springs pick up from wherever this leaves the body
                return shown;
            }
            if (r != null && r.finished()) Ragdoll.stop(p);
        } else if (s.ragdolled) {
            s.ragdolled = false;
            Ragdoll.stop(p);
            // Nothing from before the fall carries on in the springs.
            for (float[] v : s.vel) java.util.Arrays.fill(v, 0);
            java.util.Arrays.fill(s.offVel, 0);
            s.first = true;
        }

        motion(p, td, s, dt);
        Pose want = target(p, td, s, dt);
        Pose overlay = PoseStore.of(p);
        if (overlay != null) add(want, overlay);
        // Fighting: the guard comes up through the springs like any pose.
        float[] fw = new float[1];
        Pose guard = Fight.pose(p, fw);
        if (guard != null && fw[0] > 0) {
            for (Bone b : FIGHT_BONES) for (int a = 0; a < 3; a++) want.set(b, a, MathHelper.lerp(fw[0], want.get(b, a), guard.get(b, a)));
        }

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
                float k = STIFF[bi] * (ARM[bi] ? 1 + 0.7f * s.run : 1);
                float c = 2 * DAMP[bi] * (ARM[bi] ? 1 + 0.25f * s.run : 1) * (float) Math.sqrt(k);
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
        out.set(Bone.ROOT, Pose.YAW, sh.get(Bone.ROOT, Pose.YAW) + turn + Fight.bodyTurn(p, dt));

        follow(p, s, dt, out);
        hang(p, td, s, dt, out);
        touch(p, td, s, dt, out);
        // A strike lands on top of the springs: the body drives it, the fist travels its path.
        Fight.apply(p, td, out);
        s.out = out;
        return out;
    }

    /**
     * How fast they're going forward and sideways relative to where they look, from the game's own
     * movement ticks (steady, unlike frame-to-frame positions), and how that's changing; how fast they turn.
     */
    /**
     * The chain of follow-through that hanging has, for all movement: the body changes speed or turns,
     * the chest is carried along a beat late and swings past before settling, the arms hang off the
     * chest and trail it, and off the ground the legs trail the body. Each link is a damped swing driven
     * by how fast the one before it is moving, so the motion runs down the body instead of all of it
     * moving at once.
     */
    private static void follow(PlayerEntity p, State s, float dt, Pose out) {
        if (dt <= 0) return;
        boolean active = Parkour.view(p).mode() == Parkour.Mode.NONE && out.gait;
        float vy = (float) (p.getY() - p.prevY) * 20;
        if (!s.ftInit) {
            s.ftInit = true;
            s.ftFwd = s.fwd;
            s.ftSide = s.side;
            s.ftVy = vy;
            s.ftYaw = s.yawRate;
        }
        float aF = (s.fwd - s.ftFwd) / dt, aS = (s.side - s.ftSide) / dt;
        float aY = (s.yawRate - s.ftYaw) / dt;
        s.ftFwd = s.fwd;
        s.ftSide = s.side;
        s.ftYaw = s.yawRate;
        s.ftVy = vy;
        if (!active) {
            aF = aS = aY = 0;
        }
        aF = MathHelper.clamp(aF, -60, 60);
        aS = MathHelper.clamp(aS, -60, 60);
        aY = MathHelper.clamp(aY, -4000, 4000);
        float air = s.air;
        int steps = Math.max(1, (int) Math.ceil(dt / (1 / 240f)));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            // Chest: speeding up leaves it behind (leans back), stopping throws it forward; a side push
            // tips it the other way; a turn leaves it facing where it was.
            float cp0 = s.chP[1], cr0 = s.chR[1];
            swing(s.chP, 55, 0.45f, -aF * 10, h);
            swing(s.chR, 55, 0.45f, -aS * 8, h);
            swing(s.chY, 70, 0.5f, -aY * 0.35f, h);
            // Arms hang off the chest: they're thrown by its swinging (and by the body's own push).
            float chestAccP = (s.chP[1] - cp0) / h, chestAccR = (s.chR[1] - cr0) / h;
            swing(s.armP, 40, 0.35f, aF * 16 + chestAccP * 0.8f, h);
            swing(s.armR, 40, 0.35f, aS * 13 + chestAccR * 0.6f, h);
            // Legs, off the ground: they hang from the hips and trail the body's push.
            swing(s.legP, 45, 0.3f, aF * 16 * air, h);
            swing(s.legR, 45, 0.3f, aS * 12 * air, h);
        }
        float cp = MathHelper.clamp(s.chP[0], -9, 9), cr = MathHelper.clamp(s.chR[0], -7, 7), cy = MathHelper.clamp(s.chY[0], -14, 14);
        float ap = MathHelper.clamp(s.armP[0], -22, 22), ar = MathHelper.clamp(s.armR[0], -14, 14);
        float lp = MathHelper.clamp(s.legP[0], -25, 25), lr = MathHelper.clamp(s.legR[0], -12, 12);
        out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + cp);
        out.set(Bone.CHEST, Pose.ROLL, out.get(Bone.CHEST, Pose.ROLL) + cr);
        out.set(Bone.CHEST, Pose.YAW, out.get(Bone.CHEST, Pose.YAW) + cy);
        out.set(Bone.RIGHT_ARM, Pose.PITCH, out.get(Bone.RIGHT_ARM, Pose.PITCH) + ap);
        out.set(Bone.LEFT_ARM, Pose.PITCH, out.get(Bone.LEFT_ARM, Pose.PITCH) + ap);
        // Thrown sideways, both arms go the same way: one swings out, the other swings in.
        out.set(Bone.RIGHT_ARM, Pose.ROLL, out.get(Bone.RIGHT_ARM, Pose.ROLL) + ar);
        out.set(Bone.LEFT_ARM, Pose.ROLL, out.get(Bone.LEFT_ARM, Pose.ROLL) + ar);
        // The forearms lag a little more than the upper arms (the elbow gives).
        out.set(Bone.RIGHT_FOREARM, Pose.PITCH, out.get(Bone.RIGHT_FOREARM, Pose.PITCH) + Math.max(0, -ap) * 0.4f);
        out.set(Bone.LEFT_FOREARM, Pose.PITCH, out.get(Bone.LEFT_FOREARM, Pose.PITCH) + Math.max(0, -ap) * 0.4f);
        out.set(Bone.RIGHT_LEG, Pose.PITCH, out.get(Bone.RIGHT_LEG, Pose.PITCH) + lp);
        out.set(Bone.LEFT_LEG, Pose.PITCH, out.get(Bone.LEFT_LEG, Pose.PITCH) + lp);
        out.set(Bone.RIGHT_LEG, Pose.ROLL, out.get(Bone.RIGHT_LEG, Pose.ROLL) + lr);
        out.set(Bone.LEFT_LEG, Pose.ROLL, out.get(Bone.LEFT_LEG, Pose.ROLL) + lr);
        // Knees give a little as the legs swing.
        float knee = Math.abs(lp) * 0.35f;
        out.set(Bone.RIGHT_SHIN, Pose.PITCH, out.get(Bone.RIGHT_SHIN, Pose.PITCH) + knee);
        out.set(Bone.LEFT_SHIN, Pose.PITCH, out.get(Bone.LEFT_SHIN, Pose.PITCH) + knee);
    }

    /** One damped swing (x = {angle, speed}) at stiffness k and damping zeta, pushed by force. */
    private static void swing(float[] x, float k, float zeta, float force, float h) {
        float c = 2 * zeta * (float) Math.sqrt(k);
        x[1] += (force - k * x[0] - c * x[1]) * h;
        x[0] += x[1] * h;
    }

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
        // In the air the stride stops and the jump pose takes over (quick in, so it reads on take-off).
        boolean airborne = !(ground || special || p.isTouchingWater());
        s.air = approach(s.air, airborne ? 1 : 0, dt * (airborne ? 12 : 9));
        // Each jump leads with the other leg (holding jump alternates legs instead of repeating one).
        if (airborne && s.wasGround && p.getVelocity().y > 0) {
            s.leadRight = !s.leadRight;
            s.backJump = s.fwd < -1.0f;
        }
        // A backwards hop: even feet and hands up straight away (no waiting for the fall to react).
        s.backW = approach(s.backW, airborne && s.backJump ? 1 : 0, dt * (airborne ? 30 : 9));
        float w = s.gaitW * (1 - s.air);
        // Fast walk (double-tap forward) and run (holding sprint) look different: see Move.
        float mvNow = (float) Math.sqrt(s.fwd * s.fwd + s.side * s.side);
        Move.Mode md = Move.of(p, mvNow);
        s.run = approach(s.run, md == Move.Mode.RUN && ground ? 1 : 0, dt * 4);
        s.fast = approach(s.fast, md == Move.Mode.FAST && ground ? 1 : 0, dt * 4);
        s.crouch = approach(s.crouch, p.isInSneakingPose() ? 1 : 0, dt * 7);

        // Landing: the body sinks into it and comes back up, deeper after a long fall.
        double vy = p.getVelocity().y;
        if (!ground) s.fallSpeed = Math.max(s.fallSpeed, -vy);
        if (p.isTouchingWater()) s.fallSpeed = 0; // water takes the fall: no landing squash on the bottom
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
        o.set(Bone.RIGHT_ARM, Pose.ROLL, 5 + w * (2 + 9 * fast + 17 * run));
        o.set(Bone.LEFT_ARM, Pose.ROLL, -5 - w * (2 + 9 * fast + 17 * run));
        // The arms answer the body: flung out of turns, dragged behind a strafe, thrown back harder as
        // you set off. Both swing the same way (same roll as the body's lean, so the hands trail it).
        float swingOut = w * (MathHelper.clamp(s.yawRate * 0.025f, -8, 8) + sideK * 7) * (0.5f + 0.5f * (fast + run));
        add(o, Bone.RIGHT_ARM, Pose.ROLL, swingOut);
        add(o, Bone.LEFT_ARM, Pose.ROLL, swingOut);
        float kick = run * w * MathHelper.clamp(s.accFwd * 0.8f, -5, 8);
        add(o, Bone.RIGHT_ARM, Pose.PITCH, kick);
        add(o, Bone.LEFT_ARM, Pose.PITCH, kick);
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

        // In the air: one leg out. The leg that was forward at take-off drives up, knee high; the other
        // trails behind; the opposite arm reaches forward. Coming down, the legs gather under the body
        // and the arms open for balance, ready for the landing.
        float rising = (float) MathHelper.clamp(vy / 0.42, -1, 1);
        float up = Math.max(0, rising), down = Math.max(0, -rising), mid = 1 - up - down;
        float ai = s.air;
        float leadHip = -55 * up - 42 * mid - 24 * down, leadKnee = 88 * up + 70 * mid + 38 * down;
        float trailHip = 30 * up + 18 * mid + 4 * down, trailKnee = 32 * up + 34 * mid + 26 * down;
        Bone leadLeg = s.leadRight ? Bone.RIGHT_LEG : Bone.LEFT_LEG, trailLeg = s.leadRight ? Bone.LEFT_LEG : Bone.RIGHT_LEG;
        float bw = s.backW, one = ai * (1 - bw), even = ai * bw;
        add(o, leadLeg, Pose.PITCH, one * leadHip);
        add(o, trailLeg, Pose.PITCH, one * trailHip);
        if (s.leadRight) {
            rKnee += one * leadKnee;
            lKnee += one * trailKnee;
        } else {
            lKnee += one * leadKnee;
            rKnee += one * trailKnee;
        }
        // Backwards: both legs tucked the same, a shoulder's width apart.
        float evenHip = -30 * up - 22 * mid - 12 * down, evenKnee = 55 * up + 46 * mid + 30 * down;
        add(o, Bone.RIGHT_LEG, Pose.PITCH, even * evenHip);
        add(o, Bone.LEFT_LEG, Pose.PITCH, even * evenHip);
        add(o, Bone.RIGHT_LEG, Pose.ROLL, even * 5);
        add(o, Bone.LEFT_LEG, Pose.ROLL, -even * 5);
        rKnee += even * evenKnee;
        lKnee += even * evenKnee;
        Bone fwdArm = s.leadRight ? Bone.LEFT_ARM : Bone.RIGHT_ARM, backArm = s.leadRight ? Bone.RIGHT_ARM : Bone.LEFT_ARM;
        add(o, fwdArm, Pose.PITCH, one * (-48 * up - 34 * mid - 18 * down));
        add(o, backArm, Pose.PITCH, one * (32 * up + 20 * mid - 8 * down));
        add(o, Bone.RIGHT_ARM, Pose.ROLL, one * (8 + 20 * down));
        add(o, Bone.LEFT_ARM, Pose.ROLL, -one * (8 + 20 * down));
        // ...and the hands come up and out together for balance, from the moment the feet leave.
        add(o, Bone.RIGHT_ARM, Pose.PITCH, even * -105);
        add(o, Bone.LEFT_ARM, Pose.PITCH, even * -105);
        add(o, Bone.RIGHT_ARM, Pose.ROLL, even * 28);
        add(o, Bone.LEFT_ARM, Pose.ROLL, -even * 28);
        add(o, Bone.RIGHT_FOREARM, Pose.PITCH, ai * 28);
        add(o, Bone.LEFT_FOREARM, Pose.PITCH, ai * 28);
        add(o, Bone.CHEST, Pose.PITCH, ai * (9 * up + 4 * mid - 2 * down));

        // Falling fast: the body reacts. Arms flung out and windmilling, legs cycling for footing that
        // isn't there.
        float fear = s.air * MathHelper.clamp((float) (-vy - 0.55) / 0.6f, 0, 1);
        if (fear > 0.01f) {
            float w1 = MathHelper.sin(t * 9.5f), w2 = MathHelper.sin(t * 7.3f + 1.3f);
            Pose flail = new Pose();
            flail.set(Bone.HEAD, -15, 0, 0);
            flail.set(Bone.CHEST, -8 + 6 * w2, 6 * w1, 0);
            flail.set(Bone.RIGHT_ARM, -110 + 55 * w1, 0, 62 + 15 * w2).set(Bone.RIGHT_FOREARM, 25 + 15 * w2, 0, 0);
            flail.set(Bone.LEFT_ARM, -110 - 55 * w1, 0, -62 + 15 * w2).set(Bone.LEFT_FOREARM, 25 - 15 * w2, 0, 0);
            flail.set(Bone.RIGHT_LEG, -40 + 32 * w2, 0, 8).set(Bone.RIGHT_SHIN, 70 - 30 * w2, 0, 0);
            flail.set(Bone.LEFT_LEG, -40 - 32 * w2, 0, -8).set(Bone.LEFT_SHIN, 70 + 30 * w2, 0, 0);
            for (Bone b : Bone.values()) {
                if (b == Bone.ROOT) continue;
                for (int ax = 0; ax < 3; ax++) o.set(b, ax, MathHelper.lerp(fear * 0.85f, o.get(b, ax), flail.get(b, ax)));
            }
            rKnee = MathHelper.lerp(fear * 0.85f, rKnee, flail.get(Bone.RIGHT_SHIN, Pose.PITCH));
            lKnee = MathHelper.lerp(fear * 0.85f, lKnee, flail.get(Bone.LEFT_SHIN, Pose.PITCH));
        }

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

        // Parkour takes the body over (the springs carry it in and out smoothly).
        Parkour.View pv = Parkour.view(p);
        if (pv.mode() != Parkour.Mode.NONE) {
            Pose stand = new Pose();
            float k = pv.progress();
            Pose want;
            float weight = 1;
            switch (pv.mode()) {
                case VAULT -> {
                    want = Presets.VAULT;
                    weight = MathHelper.sin(MathHelper.PI * Math.min(1, k * 1.1f));
                }
                case HANG, CORNER -> want = Presets.HANG;
                case RUNUP -> want = Presets.runUp(pv.phase());
                case HOP -> {
                    // Wind up (coil, leaning away from the leap), then stretch out for the next lip.
                    // Sideways: the leading hand reaches out for the far lip, the other trails lower until
                    // the first has caught. Upwards: stretch out tall, arms high, legs long beneath.
                    float wind = Parkour.hopWindShare();
                    Pose coil = Presets.coil(-pv.side());
                    Pose fly = pv.side() != 0 ? Presets.sideReach(pv.side()) : Presets.REACH;
                    want = k < wind ? Pose.lerp(Presets.HANG, coil, ease(k / wind)) : Pose.lerp(coil, fly, ease(Math.min(1, (k - wind) / 0.35f)));
                    // Sinking onto straight-ish arms before the spring (the hands stay on the lip).
                    if (k < wind) want.offset[1] += 2.5f * ease(k / wind);
                }
                case LAND -> {
                    // A caught landing: in hard, wobbling (more the rougher it was), then easing back up.
                    float r = pv.rough();
                    float sway = MathHelper.sin(pv.secs() * (11 + 6 * r)) * (0.4f + r) * (1 - k);
                    want = Presets.caught(r, sway);
                    weight = k < 0.12f ? k / 0.12f : 1 - MathHelper.clamp((k - 0.55f) / 0.45f, 0, 1);
                }
                case FACEPLANT -> {
                    // Down on the face, a bounce, lie there a beat, push up onto a knee and stand.
                    if (k < 0.18f) want = Pose.lerp(new Pose(), Presets.SPLAT, ease(k / 0.18f));
                    else if (k < 0.55f) {
                        want = Presets.SPLAT.copy();
                        float b = MathHelper.sin(MathHelper.PI * MathHelper.clamp((k - 0.18f) / 0.12f, 0, 1)) * 6;
                        want.set(Bone.ROOT, Pose.PITCH, want.get(Bone.ROOT, Pose.PITCH) - b);
                    } else if (k < 0.8f) want = Pose.lerp(Presets.SPLAT, Presets.KNEEL, ease((k - 0.55f) / 0.25f));
                    else want = Pose.lerp(Presets.KNEEL, new Pose(), ease((k - 0.8f) / 0.2f));
                }
                case PULL -> want = k < 0.45f ? Pose.lerp(Presets.HANG, Presets.PULL, k / 0.45f)
                    : k < 0.8f ? Pose.lerp(Presets.PULL, Presets.KNEE, (k - 0.45f) / 0.35f)
                    : Pose.lerp(Presets.KNEE, stand, (k - 0.8f) / 0.2f);
                default -> want = k < 0.5f ? Pose.lerp(Presets.PULL, Presets.KNEE, k / 0.5f) : Pose.lerp(Presets.KNEE, stand, (k - 0.5f) / 0.5f);
            }
            Pose mixed = Pose.lerp(o, want, MathHelper.clamp(weight, 0, 1));
            mixed.gait = o.gait;
            return mixed;
        }
        return o;
    }

    // ---- Hanging: a real dangle, hands planted ----

    /** Where the hands hold from, in model pixels (between the hands, overhead), and the feet. */
    private static final Vector3f GRIP = new Vector3f(0, -10, -3), FEET = new Vector3f(0, Rig.FEET_Y, 0);
    /** Upper arm (shoulder to elbow) and forearm (elbow to the grip in the hand), model pixels. */
    private static final float UPPER = 4f, FORE = 6.5f;

    /**
     * While hanging the body is a pendulum under the hands: it swings in from the catch and bounces off
     * the wall, trails behind when you shuffle, and gets knocked swinging by hits. The legs hang a beat
     * behind the body. Then each arm is solved (shoulder and elbow) so the hand lands exactly on its
     * spot on the ledge, whatever the body is doing.
     */
    private static void hang(PlayerEntity p, float td, State s, float dt, Pose out) {
        Parkour.Mode mode = Parkour.view(p).mode();
        boolean hanging = mode == Parkour.Mode.HANG || mode == Parkour.Mode.CORNER;
        float body = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
        if (Parkour.onWall(p)) {
            // Face the wall, not the camera (turning smoothly round corners).
            out.set(Bone.ROOT, Pose.YAW, s.shown.get(Bone.ROOT, Pose.YAW) + MathHelper.wrapDegrees(Parkour.hangYaw(p) - body));
        }
        if (hanging) {
            double yaw = Math.toRadians(Parkour.hangYaw(p));
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw); // the wall's right, as you face it
            double vR = ((p.getX() - p.prevX) * rx + (p.getZ() - p.prevZ) * rz) * 20;
            if (dt > 0) s.accR += ((float) ((vR - s.hvR) / dt) - s.accR) * Math.min(1, dt * 12);
            s.hvR = (float) vR;
            if (!s.wasHang && mode == Parkour.Mode.HANG) {
                // The catch: the legs carry on in towards the wall (and on sideways after a leap), and
                // the body sags onto the arms before they take it.
                s.dp = s.dr = 0;
                s.vp = -1.1f;
                s.vr = Parkour.view(p).side() * 2.2f;
                s.lp = s.lr = s.lvp = s.lvr = 0;
                s.sagV = 34;
            }
            if (p.hurtTime > s.lastHurt) {
                // Hit: knocked swinging away from where it came from.
                float a = (float) Math.toRadians(p.getDamageTiltYaw());
                s.vp += 2.4f * MathHelper.cos(a);
                s.vr += 2.4f * MathHelper.sin(a);
            }
            // Body: a pendulum from the hands (about half critically damped: a swing or two, then still).
            // Legs: a shorter pendulum from the hips, pushed by the body's swing, so they follow it.
            int steps = Math.max(1, (int) Math.ceil(dt / (1 / 240f)));
            float h = dt / steps, w2 = 16f, c = 4.2f, lw2 = 30f, lc = 6.5f, len = 1.9f;
            float drive = MathHelper.clamp(s.accR, -25, 25) / len;
            for (int i = 0; i < steps; i++) {
                float ap = -w2 * MathHelper.sin(s.dp) - c * s.vp;
                float ar = -w2 * MathHelper.sin(s.dr) - c * s.vr - drive;
                s.vp += ap * h;
                s.vr += ar * h;
                s.dp += s.vp * h;
                s.dr += s.vr * h;
                // The wall: feet can't swing through it; they bump off it.
                if (s.dp < -0.04f) {
                    s.dp = -0.04f;
                    if (s.vp < 0) s.vp = -s.vp * 0.3f;
                }
                s.dp = MathHelper.clamp(s.dp, -0.04f, 0.9f);
                s.dr = MathHelper.clamp(s.dr, -0.9f, 0.9f);
                s.lvp += (-lw2 * s.lp - lc * s.lvp - ap * 0.8f) * h;
                s.lvr += (-lw2 * s.lr - lc * s.lvr - ar * 0.8f) * h;
                s.lp = MathHelper.clamp(s.lp + s.lvp * h, -0.6f, 0.6f);
                s.lr = MathHelper.clamp(s.lr + s.lvr * h, -0.6f, 0.6f);
                s.sagV += (-70 * s.sag - 11 * s.sagV) * h;
                s.sag = MathHelper.clamp(s.sag + s.sagV * h, -1, 3);
            }
        } else {
            float k = (float) Math.exp(-dt * 10);
            s.dp *= k;
            s.dr *= k;
            s.lp *= k;
            s.lr *= k;
            s.sag *= k;
            s.sagV = 0;
            s.vp = s.vr = s.lvp = s.lvr = 0;
            s.accR = 0;
        }
        s.wasHang = hanging;
        s.lastHurt = p.hurtTime;
        out.offset[1] += s.sag;
        if (Math.abs(s.dp) + Math.abs(s.dr) + Math.abs(s.lp) + Math.abs(s.lr) > 1e-4f) {
            // Swing the body about the grip (keep the grip where it was).
            float pitch0 = out.get(Bone.ROOT, Pose.PITCH), roll0 = out.get(Bone.ROOT, Pose.ROLL), yaw0 = out.get(Bone.ROOT, Pose.YAW);
            float pitch1 = pitch0 + (float) Math.toDegrees(s.dp), roll1 = roll0 + (float) Math.toDegrees(s.dr);
            Vector3f arm = new Vector3f(GRIP).sub(FEET);
            Vector3f a0 = rot(roll0, yaw0, pitch0).transform(new Vector3f(arm));
            Vector3f a1 = rot(roll1, yaw0, pitch1).transform(new Vector3f(arm));
            out.set(Bone.ROOT, Pose.PITCH, pitch1);
            out.set(Bone.ROOT, Pose.ROLL, roll1);
            out.offset[0] += a0.x - a1.x;
            out.offset[1] += a0.y - a1.y;
            out.offset[2] += a0.z - a1.z;
            // The legs follow the body a beat late; the knees give a little as they swing.
            float lp = (float) Math.toDegrees(s.lp), lr = (float) Math.toDegrees(s.lr);
            for (Bone b : new Bone[] {Bone.RIGHT_LEG, Bone.LEFT_LEG}) {
                out.set(b, Pose.PITCH, out.get(b, Pose.PITCH) + lp);
                out.set(b, Pose.ROLL, out.get(b, Pose.ROLL) + lr);
            }
            float give = Math.min(25, Math.abs(lp) * 0.6f);
            out.set(Bone.RIGHT_SHIN, Pose.PITCH, out.get(Bone.RIGHT_SHIN, Pose.PITCH) + give);
            out.set(Bone.LEFT_SHIN, Pose.PITCH, out.get(Bone.LEFT_SHIN, Pose.PITCH) + give);
        }
        float weight = Parkour.handWeight(p);
        Vec3d[] ideal = weight > 0 ? Parkour.hands(p, td) : null;
        if (ideal == null) {
            s.plant = null;
            return;
        }
        Vec3d[] hands = Parkour.exactHands(p) ? ideal : step(s, ideal, dt);
        Vec3d pos = p.getLerpedPos(td);
        Matrix4f frame = armFrame(out).invert();
        reach(out, Bone.RIGHT_ARM, Bone.RIGHT_FOREARM, new Vector3f(-5, 2, 0), toModel(hands[0], pos, body), frame, weight);
        reach(out, Bone.LEFT_ARM, Bone.LEFT_FOREARM, new Vector3f(5, 2, 0), toModel(hands[1], pos, body), frame, weight);
    }

    /**
     * Hand over hand: a hand stays planted until its spot has moved on a bit, then reaches over to the new
     * spot in a quick little arc. One hand at a time, the one that's furthest behind first.
     */
    private static Vec3d[] step(State s, Vec3d[] ideal, float dt) {
        if (s.plant == null) {
            s.plant = new Vec3d[] {ideal[0], ideal[1]};
            s.stepFrom = new Vec3d[2];
            s.stepT[0] = s.stepT[1] = -1;
        }
        Vec3d[] shown = new Vec3d[2];
        boolean busy = s.stepT[0] >= 0 || s.stepT[1] >= 0;
        if (!busy) {
            int far = s.plant[0].squaredDistanceTo(ideal[0]) >= s.plant[1].squaredDistanceTo(ideal[1]) ? 0 : 1;
            if (s.plant[far].squaredDistanceTo(ideal[far]) > 0.15 * 0.15) {
                s.stepFrom[far] = s.plant[far];
                s.stepT[far] = 0;
            }
        }
        for (int i = 0; i < 2; i++) {
            if (s.stepT[i] >= 0) {
                s.stepT[i] += dt / 0.13f;
                float k = Math.min(1, s.stepT[i]);
                float e = k * k * (3 - 2 * k);
                Vec3d v = s.stepFrom[i].lerp(ideal[i], e);
                shown[i] = v.add(0, 0.07 * MathHelper.sin(MathHelper.PI * k), 0);
                if (k >= 1) {
                    s.plant[i] = ideal[i];
                    s.stepT[i] = -1;
                }
            } else {
                // A planted hand stays put on the edge (only drifting if the spot itself jumps far away).
                if (s.plant[i].squaredDistanceTo(ideal[i]) > 0.6 * 0.6) s.plant[i] = ideal[i];
                shown[i] = s.plant[i];
            }
        }
        return shown;
    }

    private static Quaternionf rot(float rollDeg, float yawDeg, float pitchDeg) {
        return new Quaternionf().rotationZYX((float) Math.toRadians(rollDeg), (float) Math.toRadians(yawDeg), (float) Math.toRadians(pitchDeg));
    }

    /** A point in the world, in this player's model pixels (undoing the renderer's turn, flip and scale). */
    static Vector3f toModel(Vec3d w, Vec3d pos, float bodyYaw) {
        Vector3f v = new Vector3f((float) (w.x - pos.x), (float) (w.y - pos.y), (float) (w.z - pos.z));
        new Quaternionf().rotationY((float) Math.toRadians(-(180 - bodyYaw))).transform(v);
        v.x = -v.x;
        v.y = -v.y;
        v.div(0.9375f);
        v.y += 1.501f;
        return v.mul(16);
    }

    /** The arms' parent frame: the body's offset, lean and chest bend (as Rig applies them), in pixels. */
    static Matrix4f armFrame(Pose p) {
        Matrix4f m = new Matrix4f().translate(p.offset[0], p.offset[1], p.offset[2]);
        m.translate(0, Rig.FEET_Y, 0).rotate(rot(p.get(Bone.ROOT, Pose.ROLL), p.get(Bone.ROOT, Pose.YAW), p.get(Bone.ROOT, Pose.PITCH))).translate(0, -Rig.FEET_Y, 0);
        m.translate(0, Rig.WAIST_Y, 0).rotate(rot(p.get(Bone.CHEST, Pose.ROLL), p.get(Bone.CHEST, Pose.YAW), p.get(Bone.CHEST, Pose.PITCH))).translate(0, -Rig.WAIST_Y, 0);
        return m;
    }

    /**
     * Two-bone IK: the shoulder and elbow angles that put the hand on target. The elbow bend comes from
     * the distance (law of cosines), then the shoulder turns the bent arm onto the target. Out of reach,
     * the arm points straight at it.
     */
    /** As reach, with the elbow pointing towards pole (in the arm's parent frame) instead of hanging down. */
    static void reach(Pose out, Bone arm, Bone fore, Vector3f shoulder, Vector3f target, Matrix4f invFrame, float weight, Vector3f pole) {
        solve(out, arm, fore, shoulder, target, invFrame, weight, UPPER, FORE, -1, (float) Math.toRadians(-90), pole);
    }

    static void reach(Pose out, Bone arm, Bone fore, Vector3f shoulder, Vector3f target, Matrix4f invFrame, float weight) {
        // Elbows hang down and a little back and out, like a relaxed arm's do.
        Vector3f pole = new Vector3f(Math.signum(shoulder.x) * 0.3f, 1, 0.45f);
        solve(out, arm, fore, shoulder, target, invFrame, weight, UPPER, FORE, -1, (float) Math.toRadians(-90), pole);
    }

    /**
     * Two-bone IK without flips: the bend comes from the reach (law of cosines); the limb is then turned
     * from a reference (arms forward, legs down) onto the target by the shortest rotation, so it moves
     * smoothly through every direction instead of jumping where angles wrap. sign: -1 elbows bend
     * forward, +1 knees bend back.
     */
    private static void solve(Pose out, Bone upper, Bone lower, Vector3f root, Vector3f target, Matrix4f inv, float weight, float l1, float l2, float sign, float basePitch, Vector3f pole) {
        Vector3f d = inv.transformPosition(new Vector3f(target)).sub(root);
        float n = d.length();
        if (n < 1e-3f || weight <= 0) return;
        float dist = MathHelper.clamp(n, Math.abs(l1 - l2) + 0.2f, l1 + l2 - 0.05f);
        float cosIn = MathHelper.clamp((l1 * l1 + l2 * l2 - dist * dist) / (2 * l1 * l2), -1, 1);
        float bend = (float) (Math.PI - Math.acos(cosIn));
        Vector3f h = new Vector3f(0, l1 + l2 * MathHelper.cos(bend), sign * l2 * MathHelper.sin(bend));
        Quaternionf base = new Quaternionf().rotationX(basePitch);
        Vector3f hb = base.transform(new Vector3f(h)).normalize();
        Vector3f axis = new Vector3f(d).div(n);
        Quaternionf q = new Quaternionf().rotationTo(hb, axis).mul(base);
        // Then twist the bent limb about the reach line until the elbow (or knee) points the natural
        // way, instead of wherever the shortest turn happened to leave it.
        Vector3f mid = q.transform(new Vector3f(0, l1, 0));
        Vector3f ep = new Vector3f(mid).sub(new Vector3f(axis).mul(mid.dot(axis)));
        Vector3f pp = new Vector3f(pole).sub(new Vector3f(axis).mul(pole.dot(axis)));
        if (ep.lengthSquared() > 1e-4f && pp.lengthSquared() > 1e-4f) {
            float ang = (float) Math.atan2(axis.dot(new Vector3f(ep).cross(pp)), ep.dot(pp));
            q = new Quaternionf().rotationAxis(ang, axis).mul(q);
        }
        Vector3f e = new org.joml.Matrix3f().set(q).getEulerAnglesZYX(new Vector3f());
        float pd = (float) Math.toDegrees(e.x), yd = (float) Math.toDegrees(e.y), rd = (float) Math.toDegrees(e.z), bd = (float) Math.toDegrees(bend);
        // The same turn can be written two ways (pitch+180, 180-yaw, roll+180). Use the one nearer the
        // arm's current angles, and blend the short way round: blending between the two ways would swing
        // the arm through its other side (arms crossing on a fast catch).
        float cp = out.get(upper, Pose.PITCH), cy = out.get(upper, Pose.YAW), cr = out.get(upper, Pose.ROLL);
        float ap = pd + 180, ay = 180 - yd, ar = rd + 180;
        float d1 = Math.abs(MathHelper.wrapDegrees(pd - cp)) + Math.abs(MathHelper.wrapDegrees(yd - cy)) + Math.abs(MathHelper.wrapDegrees(rd - cr));
        float d2 = Math.abs(MathHelper.wrapDegrees(ap - cp)) + Math.abs(MathHelper.wrapDegrees(ay - cy)) + Math.abs(MathHelper.wrapDegrees(ar - cr));
        if (d2 < d1) {
            pd = ap;
            yd = ay;
            rd = ar;
        }
        out.set(upper, cp + MathHelper.wrapDegrees(pd - cp) * weight, cy + MathHelper.wrapDegrees(yd - cy) * weight, cr + MathHelper.wrapDegrees(rd - cr) * weight);
        out.set(lower, MathHelper.lerp(weight, out.get(lower, Pose.PITCH), bd), 0, 0);
    }

    /**
     * Close to a wall in front (walking up to it, or jumping up against it) the hands come up and press
     * flat against it, elbows bending the closer you get. Starts within about a block. Side on to a wall,
     * the arm on that side rests its hand on it and the body keeps clear of it instead of sinking in.
     */
    private static void touch(PlayerEntity p, float td, State s, float dt, Pose out) {
        boolean free = Parkour.view(p).mode() == Parkour.Mode.NONE && !p.isSwimming() && !p.hasVehicle()
            && !p.getAbilities().flying && !p.isSleeping() && !p.isSpectator() && !p.isInSneakingPose();
        Vec3d pos = p.getLerpedPos(td);
        float yaw = MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw);
        float body = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        double y = pos.y + (p.isOnGround() ? 1.2 : 1.4);
        float k = 1 - (float) Math.exp(-dt * 9);

        // Front.
        double hit = free ? probe(p, pos.x, pos.z, fx, fz, y, 0.3, 1.0) : -1;
        float want = hit < 0 ? 0 : MathHelper.clamp((float) (0.95 - hit) / 0.45f, 0, 1);
        s.wallW += (want - s.wallW) * k;
        if (hit >= 0) s.wallHit = hit;

        // Sides, across the body (its own facing, so the shoulders are where the probe is).
        double bx = -Math.sin(Math.toRadians(body)), bz = Math.cos(Math.toRadians(body));
        double rx = -bz, rz = bx; // the body's right
        double sy = pos.y + 1.3;
        for (int i = 0; i < 2; i++) {
            double sx = i == 0 ? rx : -rx, sz = i == 0 ? rz : -rz;
            double h = free ? probe(p, pos.x, pos.z, sx, sz, sy, 0.22, 0.7) : -1;
            float sw = h < 0 ? 0 : MathHelper.clamp((float) (0.7 - h) / 0.3f, 0, 1);
            s.sideW[i] += (sw - s.sideW[i]) * k;
            if (h >= 0) s.sideHit[i] = h;
        }
        if (s.wallW < 0.01f && s.sideW[0] < 0.01f && s.sideW[1] < 0.01f) return;

        // Keep the shoulder (and the arm on it) out of a wall at the side: lean the whole body clear.
        for (int i = 0; i < 2; i++) {
            float w = smooth(s.sideW[i]);
            double push = Math.min(0.05, Math.max(0, 0.36 - s.sideHit[i])) * w; // just off it: hug the wall, don't stand off
            if (push < 1e-4) continue;
            double sx = i == 0 ? rx : -rx, sz = i == 0 ? rz : -rz;
            Vector3f d = toModel(pos.add(-sx * push, 0, -sz * push), pos, body).sub(toModel(pos, pos, body));
            out.offset[0] += d.x;
            out.offset[2] += d.z;
        }
        Matrix4f frame = armFrame(out).invert();
        Vector3f rs = new Vector3f(-5, 2, 0), ls = new Vector3f(5, 2, 0);

        // Both hands on the wall in front, shoulder width apart.
        float fw = smooth(s.wallW);
        if (fw > 0.01f) {
            double frx = -Math.cos(Math.toRadians(yaw)), frz = -Math.sin(Math.toRadians(yaw));
            double d = s.wallHit - 0.03;
            Vec3d base = new Vec3d(pos.x + fx * d, y, pos.z + fz * d);
            reach(out, Bone.RIGHT_ARM, Bone.RIGHT_FOREARM, rs, toModel(base.add(frx * 0.24, 0, frz * 0.24), pos, body), frame, fw);
            reach(out, Bone.LEFT_ARM, Bone.LEFT_FOREARM, ls, toModel(base.add(-frx * 0.24, 0, -frz * 0.24), pos, body), frame, fw);
        }
        // A guiding hand on the wall beside you: out ahead of the shoulder at chest height, fingertips
        // resting on the surface, the elbow easy.
        for (int i = 0; i < 2; i++) {
            float w = smooth(s.sideW[i]) * (1 - 0.6f * fw);
            if (w < 0.01f) continue;
            double sx = i == 0 ? rx : -rx, sz = i == 0 ? rz : -rz;
            double d = Math.max(0.28, s.sideHit[i] - 0.02);
            Vec3d hand = new Vec3d(pos.x + sx * d + bx * 0.42, pos.y + 1.18, pos.z + sz * d + bz * 0.42);
            if (i == 0) reach(out, Bone.RIGHT_ARM, Bone.RIGHT_FOREARM, rs, toModel(hand, pos, body), frame, w);
            else reach(out, Bone.LEFT_ARM, Bone.LEFT_FOREARM, ls, toModel(hand, pos, body), frame, w);
        }
    }

    private static float smooth(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** How far along (dx, dz) from (x, z) at height y the first solid block is, between from and to; -1 if none. */
    private static double probe(PlayerEntity p, double x, double z, double dx, double dz, double y, double from, double to) {
        for (double d = from; d <= to; d += 0.03) {
            double cx = x + dx * d, cz = z + dz * d;
            if (!p.getWorld().isSpaceEmpty(new net.minecraft.util.math.Box(cx - 0.02, y - 0.15, cz - 0.02, cx + 0.02, y + 0.15, cz + 0.02))) return d;
        }
        return -1;
    }

    /** Two-bone IK for a leg (thigh and shin, 6 pixels each; the knee bends backwards). */
    static void stride(Pose out, Bone leg, Bone shin, Vector3f hip, Vector3f target, Matrix4f invFrame) {
        solve(out, leg, shin, hip, target, invFrame, 1, 6, 6, 1, 0, new Vector3f(Math.signum(hip.x) * 0.15f, 0, -1));
    }

    /** A steady random number in -1..1 for each step. */
    private static float jitter(int step) {
        int x = step * 0x9E3779B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        return ((x & 0xFFFF) / 32767.5f) - 1;
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
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
