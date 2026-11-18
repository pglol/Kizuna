package com.pglol.aotrpg.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;

/**
 * Clean vector shapes for maps and HUD: discs, rings, arrows, pins and pills drawn as triangles
 * with a soft one-pixel edge, instead of stacks of square fills. Coordinates are floats in GUI
 * pixels, so things glide rather than step.
 */
public final class Smooth {
    private Smooth() {}

    private static final float FEATHER = 1.1f;

    private static BufferBuilder begin(VertexFormat.DrawMode mode) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        return Tessellator.getInstance().begin(mode, VertexFormats.POSITION_COLOR);
    }

    private static void end(BufferBuilder b) {
        var built = b.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.enableCull();
    }

    private static int clear(int argb) {
        return argb & 0x00FFFFFF;
    }

    private static int segs(float r) {
        return Math.max(16, Math.min(160, (int) (r * 2.2f)));
    }

    /** A filled disc with a soft edge. */
    public static void disc(DrawContext c, float x, float y, float r, int argb) {
        if (r <= 0) return;
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        int n = segs(r);
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        float ri = Math.max(0, r - FEATHER * 0.5f), ro = r + FEATHER * 0.5f;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            b.vertex(m, x, y, 0).color(argb);
            b.vertex(m, x + c0 * ri, y + s0 * ri, 0).color(argb);
            b.vertex(m, x + c1 * ri, y + s1 * ri, 0).color(argb);
            quad(b, m, x + c0 * ri, y + s0 * ri, x + c1 * ri, y + s1 * ri, x + c1 * ro, y + s1 * ro, x + c0 * ro, y + s0 * ro, argb, clear(argb));
        }
        end(b);
    }

    /** A ring of this width (centred on r), soft both sides. */
    public static void ring(DrawContext c, float x, float y, float r, float width, int argb) {
        if (r <= 0) return;
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        int n = segs(r);
        float hw = Math.max(0.25f, width / 2 - FEATHER * 0.5f);
        float[] rad = {r - hw - FEATHER, r - hw, r + hw, r + hw + FEATHER};
        int[] col = {clear(argb), argb, argb, clear(argb)};
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            for (int k = 0; k < 3; k++) {
                float ra = Math.max(0, rad[k]), rb = Math.max(0, rad[k + 1]);
                quad(b, m, x + c0 * ra, y + s0 * ra, x + c1 * ra, y + s1 * ra, x + c1 * rb, y + s1 * rb, x + c0 * rb, y + s0 * rb, col[k], col[k + 1]);
            }
        }
        end(b);
    }

    /** A soft glow: full in the middle, gone at r. */
    public static void glow(DrawContext c, float x, float y, float r, int argb) {
        if (r <= 0) return;
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        int n = segs(r);
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            b.vertex(m, x, y, 0).color(argb);
            b.vertex(m, x + (float) Math.cos(a0) * r, y + (float) Math.sin(a0) * r, 0).color(clear(argb));
            b.vertex(m, x + (float) Math.cos(a1) * r, y + (float) Math.sin(a1) * r, 0).color(clear(argb));
        }
        end(b);
    }

    /** A marker dot: a dark rim and a coloured middle. */
    public static void dot(DrawContext c, float x, float y, float r, int argb) {
        disc(c, x, y, r + 1.2f, 0xD0000000);
        disc(c, x, y, r, argb);
    }

    /** A diamond (objectives). */
    public static void diamond(DrawContext c, float x, float y, float r, int argb) {
        poly(c, new float[] {x, y - r - 1.4f, x + r + 1.4f, y, x, y + r + 1.4f, x - r - 1.4f, y}, 0xD0000000);
        poly(c, new float[] {x, y - r, x + r, y, x, y + r, x - r, y}, argb);
    }

    /** The player's arrow, pointing along yaw (degrees, Minecraft's: 0 = south). */
    public static void arrow(DrawContext c, float x, float y, float yaw, float size, int argb) {
        double a = Math.toRadians(yaw);
        float fx = (float) -Math.sin(a), fy = (float) Math.cos(a), rx = -fy, ry = fx;
        float[] pts = {
            x + fx * size, y + fy * size,
            x - fx * size * 0.7f + rx * size * 0.75f, y - fy * size * 0.7f + ry * size * 0.75f,
            x - fx * size * 0.3f, y - fy * size * 0.3f,
            x - fx * size * 0.7f - rx * size * 0.75f, y - fy * size * 0.7f - ry * size * 0.75f};
        float[] rim = new float[pts.length];
        for (int i = 0; i < pts.length; i += 2) {
            float dx = pts[i] - x, dy = pts[i + 1] - y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            float k = len < 1e-3 ? 1 : (len + 1.5f) / len;
            rim[i] = x + dx * k;
            rim[i + 1] = y + dy * k;
        }
        poly(c, rim, 0xE0000000);
        poly(c, pts, argb);
    }

    /** A soft cone of view from the player. */
    public static void cone(DrawContext c, float x, float y, float yaw, float len, float spread, int argb) {
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a0 = Math.toRadians(yaw) - spread / 2 + spread * i / n, a1 = Math.toRadians(yaw) - spread / 2 + spread * (i + 1) / n;
            b.vertex(m, x, y, 0).color(argb);
            b.vertex(m, x - (float) Math.sin(a0) * len, y + (float) Math.cos(a0) * len, 0).color(clear(argb));
            b.vertex(m, x - (float) Math.sin(a1) * len, y + (float) Math.cos(a1) * len, 0).color(clear(argb));
        }
        end(b);
    }

    /** A map pin: a round head on a point, its tip at x, y. */
    public static void pin(DrawContext c, float x, float y, int argb) {
        poly(c, new float[] {x, y + 1.2f, x - 4.6f, y - 7, x + 4.6f, y - 7}, 0xD0000000);
        disc(c, x, y - 9, 5.2f, 0xD0000000);
        poly(c, new float[] {x, y, x - 3.6f, y - 7, x + 3.6f, y - 7}, argb);
        disc(c, x, y - 9, 4, argb);
        disc(c, x, y - 9, 1.5f, 0xFFFFFFFF);
    }

    /** A rounded box (label backs, panels). */
    public static void pill(DrawContext c, float x0, float y0, float x1, float y1, int argb) {
        float r = Math.min(4, Math.min(x1 - x0, y1 - y0) / 2);
        c.fill(Math.round(x0 + r), Math.round(y0), Math.round(x1 - r), Math.round(y1), argb);
        c.fill(Math.round(x0), Math.round(y0 + r), Math.round(x0 + r), Math.round(y1 - r), argb);
        c.fill(Math.round(x1 - r), Math.round(y0 + r), Math.round(x1), Math.round(y1 - r), argb);
        quarter(c, x0 + r, y0 + r, r, Math.PI, argb);
        quarter(c, x1 - r, y0 + r, r, Math.PI * 1.5, argb);
        quarter(c, x1 - r, y1 - r, r, 0, argb);
        quarter(c, x0 + r, y1 - r, r, Math.PI * 0.5, argb);
    }

    private static void quarter(DrawContext c, float x, float y, float r, double from, int argb) {
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        int n = 6;
        for (int i = 0; i < n; i++) {
            double a0 = from + Math.PI / 2 * i / n, a1 = from + Math.PI / 2 * (i + 1) / n;
            b.vertex(m, x, y, 0).color(argb);
            b.vertex(m, x + (float) Math.cos(a0) * r, y + (float) Math.sin(a0) * r, 0).color(argb);
            b.vertex(m, x + (float) Math.cos(a1) * r, y + (float) Math.sin(a1) * r, 0).color(argb);
        }
        end(b);
    }

    /** A filled ring between two radii (map regions), with soft edges. */
    public static void annulus(DrawContext c, float x, float y, float r0, float r1, int argb) {
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        int n = segs(r1);
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            quad(b, m, x + c0 * r0, y + s0 * r0, x + c1 * r0, y + s1 * r0, x + c1 * r1, y + s1 * r1, x + c0 * r1, y + s0 * r1, argb, argb);
        }
        end(b);
    }

    /** A convex polygon (x, y pairs) as a fan. */
    public static void poly(DrawContext c, float[] p, int argb) {
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        BufferBuilder b = begin(VertexFormat.DrawMode.TRIANGLES);
        for (int i = 2; i + 3 < p.length; i += 2) {
            b.vertex(m, p[0], p[1], 0).color(argb);
            b.vertex(m, p[i], p[i + 1], 0).color(argb);
            b.vertex(m, p[i + 2], p[i + 3], 0).color(argb);
        }
        end(b);
    }

    /** Two triangles: a-b on the inside colour, c-d on the outside one. */
    private static void quad(BufferBuilder b, Matrix4f m, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int in, int out) {
        b.vertex(m, ax, ay, 0).color(in);
        b.vertex(m, bx, by, 0).color(in);
        b.vertex(m, cx, cy, 0).color(out);
        b.vertex(m, ax, ay, 0).color(in);
        b.vertex(m, cx, cy, 0).color(out);
        b.vertex(m, dx, dy, 0).color(out);
    }

    /** Labels that don't pile on top of each other: each claims its box, later ones that hit one are skipped. */
    public static final class Labels {
        private final java.util.List<float[]> placed = new java.util.ArrayList<>();

        /** True (and the box is kept) if this box is clear. */
        public boolean claim(float x0, float y0, float x1, float y1) {
            for (float[] o : placed) if (x0 < o[2] && x1 > o[0] && y0 < o[3] && y1 > o[1]) return false;
            placed.add(new float[] {x0, y0, x1, y1});
            return true;
        }

        /** Keeps a box clear (a marker itself) without drawing anything. */
        public void block(float x0, float y0, float x1, float y1) {
            placed.add(new float[] {x0, y0, x1, y1});
        }
    }
}
