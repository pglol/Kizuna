package net.pglol.kata.client;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Fighting.Part;
import net.pglol.kata.rig.Pose;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Bodies as their limbs: a capsule for the head, the torso, each upper arm, forearm, thigh and shin,
 * where they really are this frame. For players they come from the posed skeleton, so a raised guard
 * puts the forearms in front of the face; other bodies get a standing human layout scaled to them.
 * A strike is traced along its line and lands on the first part it meets.
 */
public final class Hitbox {
    private Hitbox() {}

    public record Capsule(Part part, Vec3d a, Vec3d b, double r) { }

    public record Hit(Part part, Vec3d point, double along) { }

    /** The body's parts, in the world, now. */
    public static List<Capsule> of(Entity e, float td) {
        List<Capsule> out = new ArrayList<>();
        Vec3d pos = e.getLerpedPos(td);
        if (e instanceof PlayerEntity p) {
            float yaw = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
            Pose pose = Animator.pose(p, td);
            Vector3f[] j = Ragdoll.joints(pose);
            Vec3d[] w = new Vec3d[j.length];
            for (int i = 0; i < j.length; i++) w[i] = Ragdoll.toWorld(j[i], pos, yaw);
            out.add(new Capsule(Part.HEAD, w[Ragdoll.HEAD], w[Ragdoll.HEAD].add(0, 0.12, 0), 0.24));
            out.add(new Capsule(Part.TORSO, w[Ragdoll.NECK], w[Ragdoll.PELVIS], 0.2));
            arm(out, w, Ragdoll.RS, Ragdoll.RE, Ragdoll.RH);
            arm(out, w, Ragdoll.LS, Ragdoll.LE, Ragdoll.LH);
            out.add(new Capsule(Part.LEG, w[Ragdoll.RHIP], w[Ragdoll.RK], 0.11));
            out.add(new Capsule(Part.LEG, w[Ragdoll.RK], w[Ragdoll.RF], 0.1));
            out.add(new Capsule(Part.LEG, w[Ragdoll.LHIP], w[Ragdoll.LK], 0.11));
            out.add(new Capsule(Part.LEG, w[Ragdoll.LK], w[Ragdoll.LF], 0.1));
            return out;
        }
        // Anyone else: standing, arms at the sides, scaled to their size and turned to face their way.
        float yaw = e instanceof LivingEntity l ? MathHelper.lerpAngleDegrees(td, l.prevBodyYaw, l.bodyYaw) : e.getYaw();
        double h = e.getHeight(), half = Math.max(0.2, e.getWidth() / 2);
        double rx = -Math.cos(Math.toRadians(yaw)), rz = -Math.sin(Math.toRadians(yaw));
        Vec3d up = new Vec3d(0, 1, 0), right = new Vec3d(rx, 0, rz);
        Vec3d neck = pos.add(up.multiply(h * 0.75)), pelvis = pos.add(up.multiply(h * 0.42));
        out.add(new Capsule(Part.HEAD, pos.add(up.multiply(h * 0.84)), pos.add(up.multiply(h * 0.93)), half * 0.8));
        out.add(new Capsule(Part.TORSO, neck, pelvis, half * 0.7));
        for (int s = -1; s <= 1; s += 2) {
            Vec3d sh = neck.add(right.multiply(s * half * 1.05)), el = sh.add(up.multiply(-h * 0.17)), ha = el.add(up.multiply(-h * 0.17));
            out.add(new Capsule(Part.ARM, sh, el, half * 0.33));
            out.add(new Capsule(Part.ARM, el, ha, half * 0.3));
            Vec3d hip = pelvis.add(right.multiply(s * half * 0.38)), kn = hip.add(up.multiply(-h * 0.21)), ft = pos.add(right.multiply(s * half * 0.38));
            out.add(new Capsule(Part.LEG, hip, kn, half * 0.37));
            out.add(new Capsule(Part.LEG, kn, ft, half * 0.33));
        }
        return out;
    }

    private static void arm(List<Capsule> out, Vec3d[] w, int s, int e, int h) {
        out.add(new Capsule(Part.ARM, w[s], w[e], 0.1));
        out.add(new Capsule(Part.ARM, w[e], w[h], 0.09));
    }

    /** The first part a strike from `from` towards `to` (no further than reach) meets, fist radius included; null if it misses. */
    public static Hit trace(Entity target, float td, Vec3d from, Vec3d to, double reach) {
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 1e-4) return null;
        Vec3d end = from.add(dir.multiply(reach / len));
        Hit best = null;
        for (Capsule c : of(target, td)) {
            double[] st = closest(from, end, c.a, c.b);
            Vec3d p1 = from.add(end.subtract(from).multiply(st[0]));
            Vec3d p2 = c.a.add(c.b.subtract(c.a).multiply(st[1]));
            if (p1.distanceTo(p2) > c.r + 0.06) continue;
            if (best == null || st[0] < best.along) {
                // Where the fist meets the surface: back from the bone along the line by the part's thickness.
                Vec3d contact = p2.add(p1.subtract(p2).normalize().multiply(c.r));
                best = new Hit(c.part, contact, st[0]);
            }
        }
        return best;
    }

    public record Contact(Entity entity, Hit hit) { }

    /**
     * The first body among others that a moving fist (from a to b, radius r) touches: any part of anyone,
     * whoever it was aimed at. null if it touches nobody.
     */
    public static Contact sweep(Iterable<Entity> others, float td, Vec3d a, Vec3d b, double r) {
        Contact best = null;
        for (Entity e : others) {
            for (Capsule c : of(e, td)) {
                double[] st = closest(a, b, c.a, c.b);
                Vec3d p1 = a.add(b.subtract(a).multiply(st[0]));
                Vec3d p2 = c.a.add(c.b.subtract(c.a).multiply(st[1]));
                if (p1.distanceTo(p2) > c.r + r) continue;
                if (best == null || st[0] < best.hit.along()) {
                    Vec3d contact = p2.add(p1.subtract(p2).normalize().multiply(c.r));
                    best = new Contact(e, new Hit(c.part, contact, st[0]));
                }
            }
        }
        return best;
    }

    /** The first body along a strike's line from `from` towards `to` (up to reach), among others. */
    public static Contact traceAll(Iterable<Entity> others, float td, Vec3d from, Vec3d to, double reach) {
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 1e-4) return null;
        return sweep(others, td, from, from.add(dir.multiply(reach / len)), 0.06);
    }

    /** Closest points between segments p1-q1 and p2-q2, as their parameters (0..1) along each. */
    private static double[] closest(Vec3d p1, Vec3d q1, Vec3d p2, Vec3d q2) {
        Vec3d d1 = q1.subtract(p1), d2 = q2.subtract(p2), r = p1.subtract(p2);
        double a = d1.dotProduct(d1), e = d2.dotProduct(d2), f = d2.dotProduct(r);
        double s, t;
        if (a < 1e-9 && e < 1e-9) return new double[] {0, 0};
        if (a < 1e-9) {
            s = 0;
            t = MathHelper.clamp(f / e, 0, 1);
        } else {
            double c = d1.dotProduct(r);
            if (e < 1e-9) {
                t = 0;
                s = MathHelper.clamp(-c / a, 0, 1);
            } else {
                double b = d1.dotProduct(d2), den = a * e - b * b;
                s = den > 1e-9 ? MathHelper.clamp((b * f - c * e) / den, 0, 1) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = MathHelper.clamp(-c / a, 0, 1);
                } else if (t > 1) {
                    t = 1;
                    s = MathHelper.clamp((b - c) / a, 0, 1);
                }
            }
        }
        return new double[] {s, t};
    }
}
