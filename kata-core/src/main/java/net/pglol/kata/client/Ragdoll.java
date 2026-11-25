package net.pglol.kata.client;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A physical body: fifteen points (head, neck, pelvis, shoulders, elbows, hands, hips, knees, feet)
 * held at their lengths by constraints, thrown about by gravity and their own momentum, and stopped by
 * the real block shapes they land on, with friction. Nothing is animated: it falls the way it falls.
 *
 * It starts from the body's pose at that moment and carries on with the speed it had, so a fall from
 * height hits feet first and crumples, tumbles or slides depending on how it came in and what it hit.
 * Once it has come to rest the player gets up from wherever and however they ended up.
 *
 * Every client runs it for the players it sees, so everyone sees the same kind of fall. The rig is
 * then posed from the points: the torso from pelvis, neck and shoulders, arms and legs reach for where
 * their hands and feet are.
 */
public final class Ragdoll {
    // Points.
    static final int PELVIS = 0, NECK = 1, HEAD = 2, RS = 3, RE = 4, RH = 5, LS = 6, LE = 7, LH = 8,
        RHIP = 9, RK = 10, RF = 11, LHIP = 12, LK = 13, LF = 14, N = 15;
    private static final int[][] LINKS = {
        {PELVIS, NECK}, {NECK, HEAD}, {NECK, RS}, {NECK, LS}, {RS, LS}, {PELVIS, RHIP}, {PELVIS, LHIP}, {RHIP, LHIP},
        // Torso braces (keep it a box, not a noodle).
        {RS, RHIP}, {LS, LHIP}, {RS, LHIP}, {LS, RHIP}, {HEAD, RS}, {HEAD, LS},
        // Limbs.
        {RS, RE}, {RE, RH}, {LS, LE}, {LE, LH}, {RHIP, RK}, {RK, RF}, {LHIP, LK}, {LK, LF},
    };
    /** Limbs that may not fold flat: shoulder-hand and hip-foot keep at least this share of full length. */
    private static final int[][] SPANS = {{RS, RH}, {LS, LH}, {RHIP, RF}, {LHIP, LF}};
    private static final float RADIUS = 0.09f, GRAVITY = 26f, FRICTION = 0.72f;
    private static final float MIN_TIME = 0.9f, MAX_TIME = 3.2f, GET_UP = 1.15f;

    private static final Map<Integer, Ragdoll> ACTIVE = new HashMap<>();

    final double[] x = new double[N], y = new double[N], z = new double[N];
    final double[] px = new double[N], py = new double[N], pz = new double[N];
    final double[] rest = new double[LINKS.length], span = new double[SPANS.length];
    float age, still, getUp = -1;
    boolean faceUp;

    // Getting up: face down push up onto the hands; face up sit up; then onto a knee and stand.
    static final Pose PUSH = new Pose(), SIT = new Pose();

    static {
        PUSH.set(Bone.ROOT, 58, 0, 0);
        PUSH.set(Bone.CHEST, -12, 0, 0);
        PUSH.set(Bone.HEAD, -30, 0, 0);
        PUSH.set(Bone.RIGHT_ARM, -62, 0, 8).set(Bone.RIGHT_FOREARM, 12, 0, 0);
        PUSH.set(Bone.LEFT_ARM, -62, 0, -8).set(Bone.LEFT_FOREARM, 12, 0, 0);
        PUSH.set(Bone.RIGHT_LEG, -15, 0, 3).set(Bone.RIGHT_SHIN, 75, 0, 0);
        PUSH.set(Bone.LEFT_LEG, -5, 0, -3).set(Bone.LEFT_SHIN, 60, 0, 0);
        PUSH.offset[1] = 9;

        SIT.set(Bone.ROOT, -12, 0, 0);
        SIT.set(Bone.CHEST, 18, 0, 0);
        SIT.set(Bone.RIGHT_ARM, 35, 0, 18).set(Bone.RIGHT_FOREARM, 10, 0, 0);
        SIT.set(Bone.LEFT_ARM, 35, 0, -18).set(Bone.LEFT_FOREARM, 10, 0, 0);
        SIT.set(Bone.RIGHT_LEG, -80, 0, 6).set(Bone.RIGHT_SHIN, 40, 0, 0);
        SIT.set(Bone.LEFT_LEG, -88, 0, -6).set(Bone.LEFT_SHIN, 15, 0, 0);
        SIT.offset[1] = 11;
    }

    /** Getting up from limp at k (0..1): through the keyframes for how it lies, rotations blended as
     *  rotations (no spinning through odd angles), the head easing back to where the player looks. */
    Pose getUpPose(Pose limp, float k, float lookPitch, float lookYaw) {
        Pose[] keys = {limp, faceUp ? SIT : PUSH, Presets.KNEEL, new Pose()};
        float[] at = {0, 0.34f, 0.68f, 1};
        int seg = k < at[1] ? 0 : k < at[2] ? 1 : 2;
        float u = MathHelper.clamp((k - at[seg]) / (at[seg + 1] - at[seg]), 0, 1);
        u = u * u * (3 - 2 * u);
        Pose a = keys[seg], b = keys[seg + 1];
        Pose out = Pose.lerp(a, b, u);
        Quaternionf qa = rot(a, Bone.ROOT), qb = rot(b, Bone.ROOT);
        setRot(out, Bone.ROOT, qa.slerp(qb, u));
        // Head: the whole head's turn, from the body's (limp) to the player's look.
        float hk = MathHelper.clamp(k / 0.5f, 0, 1);
        Quaternionf limpHead = rot(limp, Bone.ROOT);
        Quaternionf look = new Quaternionf().rotationZYX(0, (float) Math.toRadians(lookYaw), (float) Math.toRadians(lookPitch));
        Vector3f he = new Matrix3f().set(limpHead.slerp(look, hk * hk * (3 - 2 * hk))).getEulerAnglesZYX(new Vector3f());
        out.set(Bone.HEAD, (float) Math.toDegrees(he.x) - lookPitch, (float) Math.toDegrees(he.y) - lookYaw, (float) Math.toDegrees(he.z));
        out.gait = true;
        return out;
    }

    private static Quaternionf rot(Pose p, Bone b) {
        return new Quaternionf().rotationZYX(p.rad(b, Pose.ROLL), p.rad(b, Pose.YAW), p.rad(b, Pose.PITCH));
    }

    private static void setRot(Pose p, Bone b, Quaternionf q) {
        Vector3f e = new Matrix3f().set(q).getEulerAnglesZYX(new Vector3f());
        p.set(b, (float) Math.toDegrees(e.x), (float) Math.toDegrees(e.y), (float) Math.toDegrees(e.z));
    }

    public static Ragdoll of(PlayerEntity p) {
        return ACTIVE.get(p.getId());
    }

    public static void stop(PlayerEntity p) {
        ACTIVE.remove(p.getId());
    }

    /** Go limp from the pose last shown, carrying on at velocity v (blocks per second). */
    public static void start(PlayerEntity p, Pose from, float bodyYaw, Vec3d v) {
        Ragdoll r = new Ragdoll();
        Vec3d pos = p.getPos();
        Vector3f[] m = joints(from == null ? new Pose() : from);
        for (int i = 0; i < N; i++) {
            Vec3d w = toWorld(m[i], pos, bodyYaw);
            r.x[i] = w.x;
            r.y[i] = w.y;
            r.z[i] = w.z;
        }
        // Keep everything just above whatever it's standing on.
        r.settleOut(p.getWorld());
        double h = 1 / 120.0;
        // A little forward topple and spin so no two falls come out alike.
        double yaw = Math.toRadians(bodyYaw);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double spin = (p.getRandom().nextDouble() - 0.5) * 0.8;
        for (int i = 0; i < N; i++) {
            double up = r.y[i] - pos.y;
            double vx = v.x + fx * up * 0.5 + fz * up * spin * 0.4, vz = v.z + fz * up * 0.5 - fx * up * spin * 0.4;
            r.px[i] = r.x[i] - vx * h;
            r.py[i] = r.y[i] - v.y * h;
            r.pz[i] = r.z[i] - vz * h;
        }
        for (int k = 0; k < LINKS.length; k++) r.rest[k] = r.dist(LINKS[k][0], LINKS[k][1]);
        for (int k = 0; k < SPANS.length; k++) r.span[k] = r.dist(SPANS[k][0], SPANS[k][1]) * 0.45;
        ACTIVE.put(p.getId(), r);
        if (ACTIVE.size() > 64) ACTIVE.clear();
    }

    private double dist(int a, int b) {
        return Math.sqrt((x[a] - x[b]) * (x[a] - x[b]) + (y[a] - y[b]) * (y[a] - y[b]) + (z[a] - z[b]) * (z[a] - z[b]));
    }

    /** True once it has got back up (done with). */
    boolean finished() {
        return getUp >= 1;
    }

    /** At rest and ready to get up (or out of time). */
    boolean resting() {
        return getUp >= 0;
    }

    // ---- physics ----

    void step(World w, float dt) {
        age += dt;
        if (getUp >= 0) {
            getUp = Math.min(1, getUp + dt / GET_UP);
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(dt / (1 / 240f)));
        double h = dt / steps;
        double moved = 0;
        for (int s = 0; s < steps; s++) {
            for (int i = 0; i < N; i++) {
                double vx = (x[i] - px[i]) * 0.996, vy = (y[i] - py[i]) * 0.996, vz = (z[i] - pz[i]) * 0.996;
                double sp = Math.sqrt(vx * vx + vy * vy + vz * vz), cap = 0.06;
                if (sp > cap) {
                    vx *= cap / sp;
                    vy *= cap / sp;
                    vz *= cap / sp;
                }
                px[i] = x[i];
                py[i] = y[i];
                pz[i] = z[i];
                x[i] += vx;
                y[i] += vy - GRAVITY * h * h;
                z[i] += vz;
                moved += Math.abs(vx) + Math.abs(vy) + Math.abs(vz);
            }
            for (int it = 0; it < 4; it++) {
                for (int k = 0; k < LINKS.length; k++) keep(LINKS[k][0], LINKS[k][1], rest[k], false);
                for (int k = 0; k < SPANS.length; k++) keep(SPANS[k][0], SPANS[k][1], span[k], true);
            }
            for (int i = 0; i < N; i++) collide(w, i);
        }
        double speed = moved / N / dt;
        still = speed < 0.35 ? still + dt : 0;
        if ((age > MIN_TIME && still > 0.35) || age > MAX_TIME) {
            getUp = 0;
            // Which way up it ended: chest towards the sky (on its back) or not.
            double ux = x[NECK] - x[PELVIS], uy = y[NECK] - y[PELVIS], uz = z[NECK] - z[PELVIS];
            double rx = x[RS] - x[LS], ry = y[RS] - y[LS], rz = z[RS] - z[LS];
            // Front = up x right (world, y up): pointing up means lying on the back.
            double fy = uz * rx - ux * rz;
            faceUp = fy > 0.25 * Math.sqrt(ux * ux + uy * uy + uz * uz) * Math.sqrt(rx * rx + ry * ry + rz * rz);
        }
    }

    /** Hold points a and b at length len (only pushing apart when min). */
    private void keep(int a, int b, double len, boolean min) {
        double dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6 || (min && d >= len)) return;
        double k = (d - len) / d * 0.5;
        x[a] += dx * k;
        y[a] += dy * k;
        z[a] += dz * k;
        x[b] -= dx * k;
        y[b] -= dy * k;
        z[b] -= dz * k;
    }

    /** Push a point out of any block it's in, the shortest way; it loses most of its speed along that
     *  surface (friction) and all of it into it. */
    private void collide(World w, int i) {
        BlockPos.Mutable bp = new BlockPos.Mutable();
        for (int bx = MathHelper.floor(x[i] - RADIUS); bx <= MathHelper.floor(x[i] + RADIUS); bx++) {
            for (int by = MathHelper.floor(y[i] - RADIUS); by <= MathHelper.floor(y[i] + RADIUS); by++) {
                for (int bz = MathHelper.floor(z[i] - RADIUS); bz <= MathHelper.floor(z[i] + RADIUS); bz++) {
                    bp.set(bx, by, bz);
                    BlockState st = w.getBlockState(bp);
                    if (st.isAir()) continue;
                    VoxelShape shape = st.getCollisionShape(w, bp);
                    if (shape.isEmpty()) continue;
                    List<Box> boxes = shape.getBoundingBoxes();
                    for (Box b0 : boxes) push(i, b0.offset(bx, by, bz).expand(RADIUS));
                }
            }
        }
    }

    private void push(int i, Box b) {
        if (!b.contains(x[i], y[i], z[i])) return;
        // Out through the face it came in by (where it was last step), else the nearest face.
        int best = -1;
        if (py[i] >= b.maxY - 1e-4) best = 3;
        else if (py[i] <= b.minY + 1e-4) best = 2;
        else if (px[i] <= b.minX + 1e-4) best = 0;
        else if (px[i] >= b.maxX - 1e-4) best = 1;
        else if (pz[i] <= b.minZ + 1e-4) best = 4;
        else if (pz[i] >= b.maxZ - 1e-4) best = 5;
        if (best < 0) {
            double[] d = {x[i] - b.minX, b.maxX - x[i], y[i] - b.minY, b.maxY - y[i], z[i] - b.minZ, b.maxZ - z[i]};
            best = 3;
            for (int k = 0; k < 6; k++) if (d[k] < d[best]) best = k;
        }
        switch (best) {
            case 0 -> { x[i] = b.minX; px[i] = x[i]; py[i] = MathHelper.lerp(FRICTION, py[i], y[i]); pz[i] = MathHelper.lerp(FRICTION, pz[i], z[i]); }
            case 1 -> { x[i] = b.maxX; px[i] = x[i]; py[i] = MathHelper.lerp(FRICTION, py[i], y[i]); pz[i] = MathHelper.lerp(FRICTION, pz[i], z[i]); }
            case 2 -> { y[i] = b.minY; py[i] = y[i]; px[i] = MathHelper.lerp(FRICTION, px[i], x[i]); pz[i] = MathHelper.lerp(FRICTION, pz[i], z[i]); }
            case 3 -> { y[i] = b.maxY; py[i] = y[i]; px[i] = MathHelper.lerp(FRICTION, px[i], x[i]); pz[i] = MathHelper.lerp(FRICTION, pz[i], z[i]); }
            case 4 -> { z[i] = b.minZ; pz[i] = z[i]; px[i] = MathHelper.lerp(FRICTION, px[i], x[i]); py[i] = MathHelper.lerp(FRICTION, py[i], y[i]); }
            default -> { z[i] = b.maxZ; pz[i] = z[i]; px[i] = MathHelper.lerp(FRICTION, px[i], x[i]); py[i] = MathHelper.lerp(FRICTION, py[i], y[i]); }
        }
    }

    private void settleOut(World w) {
        for (int i = 0; i < N; i++) {
            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
            collide(w, i);
        }
    }

    /** Where the pelvis lies now (for standing the player up there). */
    Vec3d pelvis() {
        return new Vec3d(x[PELVIS], y[PELVIS], z[PELVIS]);
    }

    // ---- the rig from the points ----

    /** The pose for these points, for a player at pos facing bodyYaw. */
    Pose pose(PlayerEntity p, Vec3d pos, float bodyYaw, float td) {
        Vector3f[] m = new Vector3f[N];
        for (int i = 0; i < N; i++) m[i] = Animator.toModel(new Vec3d(x[i], y[i], z[i]), pos, bodyYaw);
        Pose out = new Pose();
        out.gait = true;
        // Torso: up from pelvis to neck, right from left shoulder to right (the model's right is -x).
        Vector3f up = new Vector3f(m[NECK]).sub(m[PELVIS]).normalize();
        Vector3f right = new Vector3f(m[RS]).sub(m[LS]);
        right.sub(new Vector3f(up).mul(right.dot(up))).normalize();
        Vector3f c0 = new Vector3f(right).negate(), c1 = new Vector3f(up).negate();
        Vector3f c2 = new Vector3f(c0).cross(c1);
        Vector3f e = new Matrix3f(c0, c1, c2).getEulerAnglesZYX(new Vector3f());
        if (!Float.isFinite(e.x) || !Float.isFinite(e.y) || !Float.isFinite(e.z)) return out;
        out.set(Bone.ROOT, (float) Math.toDegrees(e.x), (float) Math.toDegrees(e.y), (float) Math.toDegrees(e.z));
        Quaternionf q = new Quaternionf().rotationZYX(e.z, e.y, e.x);
        Vector3f feet = new Vector3f(0, Rig.FEET_Y, 0), hipRest = new Vector3f(0, 12, 0);
        Vector3f turned = q.transform(new Vector3f(hipRest).sub(feet)).add(feet);
        Vector3f off = new Vector3f(m[PELVIS]).sub(turned);
        out.offset[0] = off.x;
        out.offset[1] = off.y;
        out.offset[2] = off.z;
        // The head turns with the body (Minecraft's own look is taken back out).
        float look = MathHelper.lerp(td, p.prevPitch, p.getPitch());
        float headYaw = MathHelper.wrapDegrees(MathHelper.lerpAngleDegrees(td, p.prevHeadYaw, p.headYaw) - bodyYaw);
        out.set(Bone.HEAD, (float) Math.toDegrees(e.x) - look, (float) Math.toDegrees(e.y) - headYaw, (float) Math.toDegrees(e.z));
        // Limbs reach for their hands and feet.
        Matrix4f inv = Animator.armFrame(out).invert();
        Animator.reach(out, Bone.RIGHT_ARM, Bone.RIGHT_FOREARM, new Vector3f(-5, 2, 0), m[RH], inv, 1);
        Animator.reach(out, Bone.LEFT_ARM, Bone.LEFT_FOREARM, new Vector3f(5, 2, 0), m[LH], inv, 1);
        Matrix4f invRoot = rootFrame(out).invert();
        Animator.stride(out, Bone.RIGHT_LEG, Bone.RIGHT_SHIN, new Vector3f(-1.9f, 12, 0), m[RF], invRoot);
        Animator.stride(out, Bone.LEFT_LEG, Bone.LEFT_SHIN, new Vector3f(1.9f, 12, 0), m[LF], invRoot);
        return out;
    }

    /** The legs' parent frame: the body's offset and lean about the feet (no chest bend), in pixels. */
    static Matrix4f rootFrame(Pose p) {
        return new Matrix4f().translate(p.offset[0], p.offset[1], p.offset[2])
            .translate(0, Rig.FEET_Y, 0)
            .rotate(new Quaternionf().rotationZYX(p.rad(Bone.ROOT, Pose.ROLL), p.rad(Bone.ROOT, Pose.YAW), p.rad(Bone.ROOT, Pose.PITCH)))
            .translate(0, -Rig.FEET_Y, 0);
    }

    /** Joint positions for a pose, in model pixels. */
    static Vector3f[] joints(Pose pose) {
        Vector3f[] m = new Vector3f[N];
        Matrix4f root = rootFrame(pose);
        Matrix4f chest = Animator.armFrame(pose);
        m[PELVIS] = root.transformPosition(new Vector3f(0, 12, 0));
        m[NECK] = chest.transformPosition(new Vector3f(0, 0, 0));
        m[HEAD] = chest.transformPosition(new Vector3f(0, -4.5f, 0));
        arm(m, chest, pose, Bone.RIGHT_ARM, Bone.RIGHT_FOREARM, new Vector3f(-5, 2, 0), RS, RE, RH);
        arm(m, chest, pose, Bone.LEFT_ARM, Bone.LEFT_FOREARM, new Vector3f(5, 2, 0), LS, LE, LH);
        leg(m, root, pose, Bone.RIGHT_LEG, Bone.RIGHT_SHIN, new Vector3f(-1.9f, 12, 0), RHIP, RK, RF);
        leg(m, root, pose, Bone.LEFT_LEG, Bone.LEFT_SHIN, new Vector3f(1.9f, 12, 0), LHIP, LK, LF);
        return m;
    }

    private static void arm(Vector3f[] m, Matrix4f frame, Pose p, Bone upper, Bone lower, Vector3f s, int a, int b, int c) {
        Quaternionf r = new Quaternionf().rotationZYX(p.rad(upper, Pose.ROLL), p.rad(upper, Pose.YAW), p.rad(upper, Pose.PITCH));
        float bend = p.rad(lower, Pose.PITCH);
        Vector3f elbow = r.transform(new Vector3f(0, 4, 0)).add(s);
        Vector3f hand = r.transform(new Vector3f(0, 4 + 6.5f * MathHelper.cos(bend), -6.5f * MathHelper.sin(bend))).add(s);
        m[a] = frame.transformPosition(new Vector3f(s));
        m[b] = frame.transformPosition(elbow);
        m[c] = frame.transformPosition(hand);
    }

    private static void leg(Vector3f[] m, Matrix4f frame, Pose p, Bone upper, Bone lower, Vector3f s, int a, int b, int c) {
        Quaternionf r = new Quaternionf().rotationZYX(p.rad(upper, Pose.ROLL), p.rad(upper, Pose.YAW), p.rad(upper, Pose.PITCH));
        float bend = p.rad(lower, Pose.PITCH);
        Vector3f knee = r.transform(new Vector3f(0, 6, 0)).add(s);
        Vector3f foot = r.transform(new Vector3f(0, 6 + 6 * MathHelper.cos(bend), 6 * MathHelper.sin(bend))).add(s);
        m[a] = frame.transformPosition(new Vector3f(s));
        m[b] = frame.transformPosition(knee);
        m[c] = frame.transformPosition(foot);
    }

    /** Model pixels to the world, for a player at pos facing bodyYaw (the renderer's own transform). */
    static Vec3d toWorld(Vector3f m, Vec3d pos, float bodyYaw) {
        Vector3f v = new Vector3f(m).div(16);
        v.y -= 1.501f;
        v.mul(0.9375f);
        v.x = -v.x;
        v.y = -v.y;
        new Quaternionf().rotationY((float) Math.toRadians(180 - bodyYaw)).transform(v);
        return new Vec3d(pos.x + v.x, pos.y + v.y, pos.z + v.z);
    }
}
