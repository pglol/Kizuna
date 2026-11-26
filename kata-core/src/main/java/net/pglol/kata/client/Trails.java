package net.pglol.kata.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Flowing trails behind the striking fist or foot of air strikes (and heavies): a thin ribbon of light
 * that follows the limb's path, widest at the limb, thinning and fading behind it in a quarter second.
 * Pale for air strikes, warm for heavies.
 */
public final class Trails {
    private Trails() {}

    private record Point(Vec3d at, long time, boolean heavy) { }

    private static final Map<Integer, ArrayDeque<Point>> TRAILS = new HashMap<>();
    private static final long LIFE = 250_000_000L;

    /** Where a striking limb is this frame. */
    static void add(int entity, Vec3d at, boolean heavy) {
        ArrayDeque<Point> q = TRAILS.computeIfAbsent(entity, k -> new ArrayDeque<>());
        long now = System.nanoTime();
        if (!q.isEmpty() && q.peekLast().at.squaredDistanceTo(at) < 0.0004) return;
        q.addLast(new Point(at, now, heavy));
        while (q.size() > 24) q.removeFirst();
        if (TRAILS.size() > 64) TRAILS.clear();
    }

    static void render(MatrixStack ms, VertexConsumerProvider vcp, Vec3d cam) {
        if (TRAILS.isEmpty()) return;
        long now = System.nanoTime();
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getLightning());
        Matrix4f m = ms.peek().getPositionMatrix();
        for (var it = TRAILS.values().iterator(); it.hasNext(); ) {
            ArrayDeque<Point> q = it.next();
            while (!q.isEmpty() && now - q.peekFirst().time > LIFE) q.removeFirst();
            if (q.size() < 2) {
                if (q.isEmpty()) it.remove();
                continue;
            }
            Point[] p = q.toArray(new Point[0]);
            for (int i = 1; i < p.length; i++) {
                Point a = p[i - 1], b = p[i];
                float ka = 1 - (now - a.time) / (float) LIFE, kb = 1 - (now - b.time) / (float) LIFE;
                if (ka <= 0 && kb <= 0) continue;
                Vec3d seg = b.at.subtract(a.at);
                Vec3d mid = a.at.add(b.at).multiply(0.5);
                Vec3d side = seg.crossProduct(mid.subtract(cam));
                if (side.lengthSquared() < 1e-8) continue;
                side = side.normalize();
                double wa = 0.09 * ka, wb = 0.09 * kb;
                int r = b.heavy ? 255 : 210, g = b.heavy ? 214 : 232, bl = b.heavy ? 160 : 255;
                vertex(vc, m, a.at.add(side.multiply(wa)).subtract(cam), r, g, bl, ka * 0.55f);
                vertex(vc, m, a.at.subtract(side.multiply(wa)).subtract(cam), r, g, bl, ka * 0.55f);
                vertex(vc, m, b.at.subtract(side.multiply(wb)).subtract(cam), r, g, bl, kb * 0.55f);
                vertex(vc, m, b.at.add(side.multiply(wb)).subtract(cam), r, g, bl, kb * 0.55f);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3d p, int r, int g, int b, float a) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, (int) (Math.max(0, a) * 255));
    }
}
