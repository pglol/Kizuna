package net.pglol.kata.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Where a strike meets a body, drawn as light, not smoke: a thin, hard shockwave ring that snaps out
 * square to the blow and is gone in a blink, and sharp impact streaks thrown out from the point (anime
 * impact lines). Heavies: a wider, slower ring, a second ring behind it, more and longer streaks, warm.
 */
public final class HitFx {
    private HitFx() {}

    private record Fx(Vec3d at, Vec3d along, long start, boolean heavy, float power, long seed) { }

    private static final List<Fx> FX = new ArrayList<>();

    static void add(Vec3d at, Vec3d along, boolean heavy, float power) {
        if (FX.size() > 32) FX.remove(0);
        long now = System.nanoTime();
        FX.add(new Fx(at, along, now, heavy, power, now ^ (long) (at.x * 1000) ^ (long) (at.z * 7919)));
    }

    static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        if (FX.isEmpty()) return;
        long now = System.nanoTime();
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getLightning());
        Matrix4f m = ms.peek().getPositionMatrix();
        for (var it = FX.iterator(); it.hasNext(); ) {
            Fx f = it.next();
            float life = f.heavy ? 0.24f : 0.14f;
            float t = (now - f.start) / 1e9f / life;
            if (t >= 1) {
                it.remove();
                continue;
            }
            Vec3d u = new Vec3d(-f.along.z, 0, f.along.x), w = new Vec3d(0, 1, 0);
            int r = f.heavy ? 255 : 225, g = f.heavy ? 222 : 240, b = f.heavy ? 170 : 255;
            // The ring: out fast, thinning as it goes.
            float reach = f.heavy ? 0.7f + 0.7f * f.power : 0.5f;
            float e = 1 - (1 - t) * (1 - t) * (1 - t);
            ring(vc, m, f.at.add(f.along.multiply(0.08 * t)).subtract(cam), u, w, 0.08 + reach * e, 0.05 * (1 - t) + 0.008, r, g, b, (1 - t) * 0.9f);
            if (f.heavy) ring(vc, m, f.at.subtract(f.along.multiply(0.12 * t)).subtract(cam), u, w, 0.05 + reach * 0.6 * e, 0.03 * (1 - t) + 0.006, r, g, b, (1 - t) * 0.55f);
            // The streaks: sharp spikes out from the point, the first half of its life only.
            if (t < 0.6f) {
                Random rnd = new Random(f.seed);
                int n = f.heavy ? 12 : 7;
                float k = t / 0.6f;
                for (int i = 0; i < n; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    Vec3d dir = u.multiply(Math.cos(a)).add(w.multiply(Math.sin(a))).add(f.along.multiply(0.35 + rnd.nextDouble() * 0.4)).normalize();
                    double len = (f.heavy ? 0.55 : 0.35) * (0.6 + rnd.nextDouble() * 0.6);
                    double in = 0.06 + len * k, outer = in + len * (1 - k) * 0.9 + 0.04;
                    spike(vc, m, f.at.add(dir.multiply(in)).subtract(cam), f.at.add(dir.multiply(outer)).subtract(cam), cam, f.at, r, g, b, (1 - k) * 0.95f);
                }
            }
        }
    }

    private static void ring(VertexConsumer vc, Matrix4f m, Vec3d c, Vec3d u, Vec3d w, double rad, double thick, int r, int g, int b, float a) {
        int seg = 28;
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2 * i / seg, a1 = Math.PI * 2 * (i + 1) / seg;
            Vec3d d0 = u.multiply(Math.cos(a0)).add(w.multiply(Math.sin(a0))), d1 = u.multiply(Math.cos(a1)).add(w.multiply(Math.sin(a1)));
            v(vc, m, c.add(d0.multiply(rad - thick)), r, g, b, 0);
            v(vc, m, c.add(d0.multiply(rad)), r, g, b, a);
            v(vc, m, c.add(d1.multiply(rad)), r, g, b, a);
            v(vc, m, c.add(d1.multiply(rad - thick)), r, g, b, 0);
            v(vc, m, c.add(d0.multiply(rad)), r, g, b, a);
            v(vc, m, c.add(d0.multiply(rad + thick * 0.5)), r, g, b, 0);
            v(vc, m, c.add(d1.multiply(rad + thick * 0.5)), r, g, b, 0);
            v(vc, m, c.add(d1.multiply(rad)), r, g, b, a);
        }
    }

    /** A thin tapering spike from a (wide, bright) to b (a point), turned to face the camera. */
    private static void spike(VertexConsumer vc, Matrix4f m, Vec3d a, Vec3d b, Vec3d cam, Vec3d at, int r, int g, int bl, float al) {
        Vec3d seg = b.subtract(a);
        Vec3d side = seg.crossProduct(a.add(b).multiply(0.5)); // relative to the camera already
        if (side.lengthSquared() < 1e-10) return;
        side = side.normalize().multiply(0.018);
        v(vc, m, a.add(side), r, g, bl, al);
        v(vc, m, a.subtract(side), r, g, bl, al);
        v(vc, m, b, r, g, bl, 0);
        v(vc, m, b, r, g, bl, 0);
    }

    private static void v(VertexConsumer vc, Matrix4f m, Vec3d p, int r, int g, int b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, (int) (Math.max(0, Math.min(1, a)) * 255));
    }
}
