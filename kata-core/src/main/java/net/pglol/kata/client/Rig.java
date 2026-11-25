package net.pglol.kata.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.pglol.kata.client.mixin.CuboidAccessor;
import net.pglol.kata.client.mixin.ModelPartAccessor;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a posed player's body parts bent: each arm and leg box is split at its middle and the lower
 * half turned about the elbow or knee (the seam gets half the turn, so the skin stays one piece), and
 * the body box bends at the waist with the head and arms carried along. Sleeves, trousers, jacket and
 * armour are the same kind of box, so they bend the same way.
 */
public final class Rig {
    private Rig() {}

    public enum Role {
        HEAD(true, Kind.NONE, true), HAT(true, Kind.NONE, true),
        BODY(false, Kind.TORSO), JACKET(false, Kind.TORSO),
        RIGHT_ARM(true, Kind.RIGHT_ELBOW), RIGHT_SLEEVE(true, Kind.RIGHT_ELBOW),
        LEFT_ARM(true, Kind.LEFT_ELBOW), LEFT_SLEEVE(true, Kind.LEFT_ELBOW),
        RIGHT_LEG(false, Kind.RIGHT_KNEE), RIGHT_PANTS(false, Kind.RIGHT_KNEE),
        LEFT_LEG(false, Kind.LEFT_KNEE), LEFT_PANTS(false, Kind.LEFT_KNEE);

        final boolean followsChest, head;
        final Kind kind;

        Role(boolean followsChest, Kind kind) {
            this(followsChest, kind, false);
        }

        Role(boolean followsChest, Kind kind, boolean head) {
            this.followsChest = followsChest;
            this.kind = kind;
            this.head = head;
        }
    }

    enum Kind { NONE, TORSO, RIGHT_ELBOW, LEFT_ELBOW, RIGHT_KNEE, LEFT_KNEE }

    /** Where the chest bends, in model pixels below the neck (the body box runs 0..12). */
    public static final float WAIST_Y = 8f;

    private static final Map<ModelPart, Role> ROLES = new WeakHashMap<>();
    /** The pose of the player being drawn right now (render thread only), or null. */
    public static Pose current;

    public static void tag(ModelPart part, Role role) {
        if (part != null) ROLES.put(part, role);
    }

    static Quaternionf euler(Pose p, Bone b) {
        return new Quaternionf().rotationZYX(p.rad(b, Pose.ROLL), p.rad(b, Pose.YAW), p.rad(b, Pose.PITCH));
    }

    /** Extra forearm length in model pixels (arms are 12; this makes them reach a little further). */
    public static final float ARM_EXTRA = 1.5f;

    /** Where the whole body leans and banks from: the feet, in model pixels. */
    public static final float FEET_Y = 24f;

    /** The whole body: offset (bob, sway, drop), then lean and bank about the feet. */
    public static void root(MatrixStack m, Pose p) {
        if (p == null || !p.shifted()) return;
        m.translate(p.offset[0] / 16f, p.offset[1] / 16f, p.offset[2] / 16f);
        if (p.moves(Bone.ROOT)) {
            m.translate(0, FEET_Y / 16f, 0);
            m.multiply(euler(p, Bone.ROOT));
            m.translate(0, -FEET_Y / 16f, 0);
        }
    }

    /** Moves the head to where the neck ends up after the body's offset, lean and chest bend, without turning it. */
    static void neck(MatrixStack m, Pose p, ModelPart head) {
        if (p == null) return;
        Matrix4f b = new Matrix4f();
        b.translate(p.offset[0] / 16f, p.offset[1] / 16f, p.offset[2] / 16f);
        if (p.moves(Bone.ROOT)) b.translate(0, FEET_Y / 16f, 0).rotate(euler(p, Bone.ROOT)).translate(0, -FEET_Y / 16f, 0);
        if (p.moves(Bone.CHEST)) b.translate(0, WAIST_Y / 16f, 0).rotate(euler(p, Bone.CHEST)).translate(0, -WAIST_Y / 16f, 0);
        Vector3f at = new Vector3f(head.pivotX / 16f, head.pivotY / 16f, head.pivotZ / 16f);
        Vector3f moved = b.transformPosition(new Vector3f(at));
        m.translate(moved.x - at.x, moved.y - at.y, moved.z - at.z);
    }

    /** Head and arms ride on the chest: turn them about the waist before their own rotation. */
    public static void chest(MatrixStack m, Pose p) {
        if (p == null || !p.moves(Bone.CHEST)) return;
        m.translate(0, WAIST_Y / 16f, 0);
        m.multiply(euler(p, Bone.CHEST));
        m.translate(0, -WAIST_Y / 16f, 0);
    }

    /** The bend for this part: elbows bend forward, knees back. */
    static Quaternionf bend(Role role, Pose p) {
        return switch (role.kind) {
            case TORSO -> p.moves(Bone.CHEST) ? euler(p, Bone.CHEST) : null;
            case RIGHT_ELBOW -> elbow(p, Bone.RIGHT_FOREARM);
            case LEFT_ELBOW -> elbow(p, Bone.LEFT_FOREARM);
            case RIGHT_KNEE -> knee(p, Bone.RIGHT_SHIN);
            case LEFT_KNEE -> knee(p, Bone.LEFT_SHIN);
            case NONE -> null;
        };
    }

    private static Quaternionf elbow(Pose p, Bone b) {
        if (!p.moves(b)) return null;
        return new Quaternionf().rotationZYX(p.rad(b, Pose.ROLL), p.rad(b, Pose.YAW), -p.rad(b, Pose.PITCH));
    }

    private static Quaternionf knee(Pose p, Bone b) {
        if (!p.moves(b)) return null;
        return new Quaternionf().rotationZYX(p.rad(b, Pose.ROLL), p.rad(b, Pose.YAW), p.rad(b, Pose.PITCH));
    }

    /** The elbow's offset for things held in the hand: turn about the elbow like the forearm does. */
    public static void forearm(MatrixStack m, ModelPart arm, boolean right, Pose p) {
        if (p == null) return;
        Quaternionf q = elbow(p, right ? Bone.RIGHT_FOREARM : Bone.LEFT_FOREARM);
        if (q != null) {
            var cubes = ((ModelPartAccessor) (Object) arm).kata$cuboids();
            if (!cubes.isEmpty()) {
                ModelPart.Cuboid c = cubes.get(0);
                float px = (c.minX + c.maxX) / 2f, py = (c.minY + c.maxY) / 2f, pz = (c.minZ + c.maxZ) / 2f;
                m.translate(px / 16f, py / 16f, pz / 16f);
                m.multiply(q);
                m.translate(-px / 16f, -py / 16f, -pz / 16f);
            }
        }
        // The hand sits at the end of the longer forearm.
        m.translate(0, ARM_EXTRA / 16f, 0);
    }

    /** Lengthens the forearm half of an arm box: the elbow stays put, the hand end moves down. */
    private static Vector3f stretch(Vector3f pos, float split, float maxY, float extra) {
        if (extra != 0 && pos.y > split && maxY > split) pos.y += extra * (pos.y - split) / (maxY - split);
        return pos;
    }

    /** Draws a tagged part of the player being posed. Returns false to let Minecraft draw it. */
    public static boolean render(ModelPart part, MatrixStack m, VertexConsumer vc, int light, int overlay, int color) {
        Pose p = current;
        if (p == null) return false;
        Role role = ROLES.get(part);
        if (role == null) return false;
        if (!part.visible) return true;
        ModelPartAccessor acc = (ModelPartAccessor) (Object) part;
        m.push();
        if (role.head) {
            // The head rides on the neck (it moves with the body's lean, twist and bob) but keeps its
            // own turn, which is where the player is looking: it never inherits the body's rotation.
            neck(m, p, part);
        } else {
            root(m, p);
            if (role.followsChest) chest(m, p);
        }
        part.rotate(m);
        if (!part.hidden) {
            Quaternionf q = bend(role, p);
            MatrixStack.Entry e = m.peek();
            for (ModelPart.Cuboid c : acc.kata$cuboids()) cuboid(c, e, vc, light, overlay, color, role.kind, q);
        }
        for (ModelPart child : acc.kata$children().values()) child.render(m, vc, light, overlay, color);
        m.pop();
        return true;
    }

    // ---- The bent box ----

    private static void cuboid(ModelPart.Cuboid c, MatrixStack.Entry e, VertexConsumer vc, int light, int overlay, int color, Kind kind, Quaternionf q) {
        ModelPart.Quad[] sides = ((CuboidAccessor) (Object) c).kata$sides();
        boolean arm = kind == Kind.RIGHT_ELBOW || kind == Kind.LEFT_ELBOW;
        float extra = arm ? ARM_EXTRA : 0, mid = (c.minY + c.maxY) / 2f;
        if (q == null) {
            for (ModelPart.Quad quad : sides) {
                Vector3f n = e.transformNormal(quad.direction, new Vector3f());
                for (ModelPart.Vertex v : quad.vertices) {
                    Vector3f pos = stretch(new Vector3f(v.pos), mid, c.maxY, extra);
                    emit(e, vc, pos.x, pos.y, pos.z, v.u, v.v, n, light, overlay, color);
                }
            }
            return;
        }
        boolean torso = kind == Kind.TORSO;
        float split = torso ? Math.max(c.minY, Math.min(c.maxY, WAIST_Y)) : (c.minY + c.maxY) / 2f;
        Vector3f pivot = torso ? new Vector3f(0, split, 0) : new Vector3f((c.minX + c.maxX) / 2f, split, (c.minZ + c.maxZ) / 2f);
        Quaternionf half = new Quaternionf().slerp(q, 0.5f);
        for (ModelPart.Quad quad : sides) {
            ModelPart.Vertex[] vs = quad.vertices;
            float qMin = Float.MAX_VALUE, qMax = -Float.MAX_VALUE;
            for (ModelPart.Vertex v : vs) {
                qMin = Math.min(qMin, v.pos.y());
                qMax = Math.max(qMax, v.pos.y());
            }
            if (qMax - qMin < 1e-4f) {
                // Top or bottom face: wholly on one side of the joint.
                boolean moving = torso ? qMin < split : qMin > split;
                Quaternionf r = moving ? q : null;
                Vector3f n = normal(e, quad.direction, r);
                for (ModelPart.Vertex v : vs) {
                    Vector3f pos = turn(stretch(new Vector3f(v.pos), split, c.maxY, extra), pivot, r);
                    emit(e, vc, pos.x, pos.y, pos.z, v.u, v.v, n, light, overlay, color);
                }
                continue;
            }
            // A side face: two halves, the seam turned half way.
            float vTop = 0, vBot = 0;
            for (ModelPart.Vertex v : vs) {
                if (Math.abs(v.pos.y() - qMin) < 1e-4f) vTop = v.v;
                else vBot = v.v;
            }
            float k = (split - qMin) / (qMax - qMin);
            float vMid = vTop + (vBot - vTop) * k;
            // The fixed half keeps its far edge where it is; the moving half turns fully.
            for (int half2 = 0; half2 < 2; half2++) {
                boolean movingHalf = half2 == 1;
                Vector3f n = normal(e, quad.direction, movingHalf ? q : half);
                for (ModelPart.Vertex v : vs) {
                    boolean atTop = Math.abs(v.pos.y() - qMin) < 1e-4f;
                    boolean farEdgeMoves = torso ? atTop : !atTop;
                    Vector3f pos = stretch(new Vector3f(v.pos), split, c.maxY, extra);
                    float vv = v.v;
                    if (farEdgeMoves == movingHalf) {
                        // This corner belongs to this half's own end.
                        if (movingHalf) turn(pos, pivot, q);
                    } else {
                        // This corner is on the seam.
                        pos.y = split;
                        vv = vMid;
                        turn(pos, pivot, half);
                    }
                    emit(e, vc, pos.x, pos.y, pos.z, v.u, vv, n, light, overlay, color);
                }
            }
        }
    }

    private static Vector3f turn(Vector3f pos, Vector3f pivot, Quaternionf q) {
        if (q == null) return pos;
        pos.sub(pivot);
        q.transform(pos);
        return pos.add(pivot);
    }

    private static Vector3f normal(MatrixStack.Entry e, Vector3f dir, Quaternionf q) {
        Vector3f d = new Vector3f(dir);
        if (q != null) q.transform(d);
        return e.transformNormal(d, new Vector3f());
    }

    private static void emit(MatrixStack.Entry e, VertexConsumer vc, float x, float y, float z, float u, float v, Vector3f n, int light, int overlay, int color) {
        Matrix4f mat = e.getPositionMatrix();
        Vector3f p = mat.transformPosition(x / 16f, y / 16f, z / 16f, new Vector3f());
        vc.vertex(p.x, p.y, p.z, color, u, v, overlay, light, n.x, n.y, n.z);
    }
}
