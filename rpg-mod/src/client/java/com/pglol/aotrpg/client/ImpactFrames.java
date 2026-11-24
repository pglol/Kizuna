package com.pglol.aotrpg.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Random;

/**
 * Impact frames, the way anime cuts a big hit: a frame gone black with white spikes bursting from
 * the point of impact, a frame gone white with black ones, then speed lines rushing in from the
 * edges as it all settles. A beat of stillness (the lens snapping in) and a jolt to the view.
 */
public final class ImpactFrames {
    private ImpactFrames() {}

    private static long at, seed;
    private static float strength;
    private static Vec3d where;
    /** Speed lines alone (a vanish, a charge): until, and how hard. */
    private static long linesUntil;
    private static float lines;

    /** A big hit at this spot (strength 0..1+). */
    public static void hit(Vec3d pos, float s) {
        long now = Util.getMeasuringTimeMs();
        if (now - at < 380 && s <= strength) return;
        at = now;
        seed = now ^ (long) (pos.x * 31 + pos.z * 17);
        strength = Math.min(1.4f, s);
        where = pos;
        EffectFx.quake(0.25f + 0.35f * s, 6);
        AdrenalineClient.punch(-10 * s);
    }

    /** Speed lines closing in from the edges for a moment. */
    public static void rush(float s, long ms) {
        if (!Settings.impactFrames) return;
        linesUntil = Util.getMeasuringTimeMs() + ms;
        lines = s;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        long now = Util.getMeasuringTimeMs();
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long t = now - at;
        if (where != null && t < 420) {
            float[] o = screen(mc, where, w, h);
            Random r = new Random(seed);
            Matrix4f m = c.getMatrices().peek().getPositionMatrix();
            if (t < 55) {
                // Frame one: black, white spikes.
                fill(m, w, h, (int) (215 * Math.min(1, strength)) << 24);
                spikes(m, o[0], o[1], w, h, r, 26, 0.05f, 0xF0FFFFFF, 0.04f);
            } else if (t < 110) {
                // Frame two: white, black spikes.
                fill(m, w, h, ((int) (170 * Math.min(1, strength)) << 24) | 0xFFFFFF);
                spikes(m, o[0], o[1], w, h, r, 22, 0.06f, 0xE0000000, 0.03f);
            } else {
                // Settling: speed lines in from the edges, thinning out.
                float k = 1 - (t - 110) / 310f;
                lines(m, o[0], o[1], w, h, r, 40, k * strength);
            }
        }
        if (now < linesUntil) {
            float k = (linesUntil - now) / 260f;
            lines(c.getMatrices().peek().getPositionMatrix(), w / 2f, h / 2f, w, h, new Random(now / 50), 34, Math.min(1, k) * lines);
        }
    }

    /** Where a world point lands on screen (the middle if it's behind you). */
    static float[] screen(MinecraftClient mc, Vec3d p, int w, int h) {
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d rel = p.subtract(cam.getPos());
        Vector3f v = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        new Quaternionf(cam.getRotation()).conjugate().transform(v);
        if (v.z >= -0.1f) return new float[] {w / 2f, h / 2f};
        double fov = Math.toRadians(mc.options.getFov().getValue());
        float focal = (float) (h / 2f / Math.tan(fov / 2));
        float x = w / 2f + v.x / -v.z * focal, y = h / 2f - v.y / -v.z * focal;
        return new float[] {Math.max(0, Math.min(w, x)), Math.max(0, Math.min(h, y))};
    }

    private static void begin() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
    }

    private static void end(BufferBuilder b) {
        var built = b.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.enableCull();
    }

    private static void fill(Matrix4f m, int w, int h, int argb) {
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        b.vertex(m, 0, 0, 0).color(argb);
        b.vertex(m, 0, h, 0).color(argb);
        b.vertex(m, w, h, 0).color(argb);
        b.vertex(m, w, 0, 0).color(argb);
        end(b);
    }

    /** Wedges bursting out from a point to past the edges. */
    private static void spikes(Matrix4f m, float cx, float cy, int w, int h, Random r, int n, float width, int argb, float jitter) {
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        float far = (float) Math.hypot(w, h) * 1.2f;
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + (r.nextDouble() - 0.5) * 0.4;
            double wd = width * (0.4 + r.nextDouble() * 1.4);
            float inner = 6 + r.nextFloat() * 22;
            float len = far * (0.45f + r.nextFloat() * 0.55f);
            float tx = cx + (float) Math.cos(a) * inner, ty = cy + (float) Math.sin(a) * inner;
            b.vertex(m, tx, ty, 0).color(argb);
            b.vertex(m, cx + (float) Math.cos(a - wd) * len, cy + (float) Math.sin(a - wd) * len, 0).color(argb);
            b.vertex(m, cx + (float) Math.cos(a + wd) * len, cy + (float) Math.sin(a + wd) * len, 0).color(argb);
            if (r.nextFloat() < jitter * 10) {
                // A split spike: a second, thinner one beside it.
                double a2 = a + wd * 2.5;
                b.vertex(m, tx, ty, 0).color(argb);
                b.vertex(m, cx + (float) Math.cos(a2 - wd * 0.4) * len * 0.8f, cy + (float) Math.sin(a2 - wd * 0.4) * len * 0.8f, 0).color(argb);
                b.vertex(m, cx + (float) Math.cos(a2 + wd * 0.4) * len * 0.8f, cy + (float) Math.sin(a2 + wd * 0.4) * len * 0.8f, 0).color(argb);
            }
        }
        end(b);
    }

    /** Manga speed lines: thin white wedges from the edges in towards a point, never reaching it. */
    private static void lines(Matrix4f m, float cx, float cy, int w, int h, Random r, int n, float k) {
        if (k <= 0.02f) return;
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        float far = (float) Math.hypot(w, h);
        int col = ((int) (150 * Math.min(1, k)) << 24) | 0xFFFFFF;
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, wd = 0.004 + r.nextDouble() * 0.01;
            float inner = far * (0.32f + r.nextFloat() * 0.22f);
            b.vertex(m, cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner, 0).color(col);
            b.vertex(m, cx + (float) Math.cos(a - wd) * far, cy + (float) Math.sin(a - wd) * far, 0).color(col);
            b.vertex(m, cx + (float) Math.cos(a + wd) * far, cy + (float) Math.sin(a + wd) * far, 0).color(col);
        }
        end(b);
    }
}
