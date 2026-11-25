package net.pglol.kata.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Fighting;
import org.joml.Matrix4f;

/**
 * The stance diamond at your feet, shown while locked on. It's laid flat on the ground and pointed
 * at your opponent. Four big faceted blades are the quadrants:
 *  - the two towards the opponent are the top stances (chest to them)
 *  - the two away from them are the bottom stances (back to them)
 * The one you're in is shaded dark. Small blades sit between them, and the guard button is in the
 * middle.
 */
public final class Stance {
    private Stance() {}

    private static final float R = 0.78f;

    static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        Entity t = Fight.targetEntity(mc);
        if (p == null || t == null || ctx.consumers() == null || ctx.matrixStack() == null) return;
        float td = ctx.tickCounter().getTickDelta(false);
        Vec3d at = p.getLerpedPos(td);
        Vec3d to = t.getLerpedPos(td).subtract(at);
        double len = Math.hypot(to.x, to.z);
        if (len < 1e-3) return;
        // Forward (towards them) and right, flat on the ground.
        double fx = to.x / len, fz = to.z / len;
        double rx = -fz, rz = fx;
        Vec3d cam = ctx.camera().getPos();
        MatrixStack ms = ctx.matrixStack();
        ms.push();
        ms.translate(at.x - cam.x, at.y - cam.y + 0.03, at.z - cam.z);
        Matrix4f m = ms.peek().getPositionMatrix();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderLayer.getDebugQuads());

        float[] c = {0, 0};
        float[] front = pt(fx, fz, 0, 0, R), back = pt(-fx, -fz, 0, 0, R), right = pt(rx, rz, 0, 0, R), left = pt(-rx, -rz, 0, 0, R);
        int active = Fight.stance();
        // Quadrants: {tip along the facing axis, tip along the side axis, stance}.
        Object[][] quads = {
            {front, right, Fighting.TR}, {front, left, Fighting.TL},
            {back, right, Fighting.BR}, {back, left, Fighting.BL},
        };
        for (Object[] q : quads) {
            float[] a = (float[]) q[0], b = (float[]) q[1];
            boolean on = (int) q[2] == active;
            float[] mid = {(a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f};
            // Two facets (one lit, one shaded) give the blade its folded, raised look.
            int lit = on ? rgba(0.30f, 0.32f, 0.40f, 0.92f) : rgba(0.96f, 0.97f, 1f, 0.82f);
            int shade = on ? rgba(0.20f, 0.21f, 0.27f, 0.92f) : rgba(0.80f, 0.82f, 0.88f, 0.82f);
            tri(vc, m, c, a, mid, lit);
            tri(vc, m, c, mid, b, shade);
        }
        // The small blades between the quadrants, on the diagonals.
        double[][] diag = {{fx + rx, fz + rz}, {fx - rx, fz - rz}, {-fx + rx, -fz + rz}, {-fx - rx, -fz - rz}};
        for (double[] d : diag) {
            double dl = Math.hypot(d[0], d[1]);
            double dx = d[0] / dl, dz = d[1] / dl, px = -dz, pz = dx;
            float[] in = pt(dx, dz, 0, 0, R * 0.42f), out = pt(dx, dz, 0, 0, R * 0.92f);
            float[] s1 = pt(dx, dz, px, pz, R * 0.6f, R * 0.13f), s2 = pt(dx, dz, -px, -pz, R * 0.6f, R * 0.13f);
            tri(vc, m, in, s1, out, rgba(0.92f, 0.93f, 0.97f, 0.6f));
            tri(vc, m, in, out, s2, rgba(0.74f, 0.76f, 0.82f, 0.6f));
        }
        // The guard button in the middle: a small white badge.
        float bw = 0.15f, bh = 0.09f;
        float[] b1 = pt(fx, fz, rx, rz, bh, -bw), b2 = pt(fx, fz, rx, rz, bh, bw), b3 = pt(fx, fz, rx, rz, -bh, bw), b4 = pt(fx, fz, rx, rz, -bh, -bw);
        int badge = rgba(1f, 1f, 1f, 0.95f);
        tri(vc, m, b1, b2, b3, badge);
        tri(vc, m, b1, b3, b4, badge);
        label(ms, ctx, mc, fx, fz);
        ms.pop();
    }

    /** The guard key's name, flat on the badge, reading from behind you towards them. */
    private static void label(MatrixStack ms, WorldRenderContext ctx, MinecraftClient mc, double fx, double fz) {
        TextRenderer tr = mc.textRenderer;
        String key = mc.options.useKey.getBoundKeyLocalizedText().getString();
        if (key.length() > 4) key = "RMB";
        ms.push();
        ms.translate(0, 0.004, 0);
        float yaw = (float) Math.toDegrees(Math.atan2(-fx, fz));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90));
        float s = 0.011f;
        ms.scale(-s, -s, s);
        float w = tr.getWidth(key);
        tr.draw(key, -w / 2, -4, 0xFF3A3D48, false, ms.peek().getPositionMatrix(), ctx.consumers(),
            TextRenderer.TextLayerType.POLYGON_OFFSET, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        ms.pop();
    }

    /** A point a along (dx, dz) plus b along (px, pz). */
    private static float[] pt(double dx, double dz, double px, double pz, float a, float b) {
        return new float[] {(float) (dx * a + px * b), (float) (dz * a + pz * b)};
    }

    private static float[] pt(double dx, double dz, double px, double pz, float a) {
        return pt(dx, dz, px, pz, a, 0);
    }

    /** One flat triangle on the ground, both sides drawn (so it shows from any angle). */
    private static void tri(VertexConsumer vc, Matrix4f m, float[] a, float[] b, float[] c, int color) {
        v(vc, m, a, color);
        v(vc, m, b, color);
        v(vc, m, c, color);
        v(vc, m, c, color);
        v(vc, m, c, color);
        v(vc, m, b, color);
        v(vc, m, a, color);
        v(vc, m, a, color);
    }

    private static void v(VertexConsumer vc, Matrix4f m, float[] p, int color) {
        vc.vertex(m, p[0], 0, p[1]).color(color);
    }

    private static int rgba(float r, float g, float b, float a) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
