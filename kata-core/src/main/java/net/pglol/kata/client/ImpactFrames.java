package net.pglol.kata.client;

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
 * The feel of a heavy landing. For the two fighters in it: a surge, a tint closing in from the edges of
 * the screen and a faint wash over the whole view (warm for the one who landed it, deep red for the one
 * it landed on), the view punching in a touch and shaking for a moment; well charged, faint speed lines
 * towards the hit too. For anyone else nearby nothing on screen at all, unless the hit is out of view:
 * then a soft glow on the edge of the screen on the side it came from.
 */
public final class ImpactFrames {
    private ImpactFrames() {}

    public static final int ATTACKER = 0, VICTIM = 1, NEARBY = 2;

    private static long at, seed;
    private static float strength;
    private static int role = -1;
    private static Vec3d where;
    /** The edge cue for a hit out of view: when, and where it was. */
    private static long cueAt;
    private static Vec3d cueWhere;
    private static float cueStrength;

    /** A heavy landed at this spot (strength 0.55 light, 1 medium), seen as attacker, victim or someone nearby. */
    public static void hit(Vec3d pos, float s, int who) {
        long now = Util.getMeasuringTimeMs();
        if (who == NEARBY) {
            cueAt = now;
            cueWhere = pos;
            cueStrength = s;
            return;
        }
        if (now - at < 300 && s <= strength) return;
        at = now;
        seed = now;
        strength = s;
        role = who;
        where = pos;
    }

    /** How strong the surge is right now (0..1+), rising fast and ebbing away. */
    private static float amp() {
        if (role < 0) return 0;
        long t = Util.getMeasuringTimeMs() - at;
        float len = 160 + 200 * strength;
        if (t < 45) return strength * t / 45f;
        float k = (float) Math.exp(-(t - 45) / len * 3);
        return k < 0.01f ? 0 : strength * k;
    }

    /** The view punching in (a multiplier on the field of view). */
    public static float fovScale() {
        return 1 - 0.06f * amp();
    }

    /** A shake of the view, in degrees (yaw, pitch). */
    public static float[] shake() {
        float a = amp();
        if (a < 0.02f) return null;
        long t = Util.getMeasuringTimeMs();
        return new float[] {(float) Math.sin(t * 0.09) * 0.9f * a, (float) Math.cos(t * 0.11) * 0.7f * a};
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        float a = amp();
        if (a > 0.01f) {
            int rgb = role == ATTACKER ? 0xFFD49A : 0xC4161C;
            // The wash over everything, then the tint pressing in from the edges.
            fill(m, w, h, argb(a * (role == ATTACKER ? 0.08f : 0.14f), rgb));
            vignette(m, w, h, argb(Math.min(0.75f, a * 0.62f), rgb), 0.24f);
            if (strength >= 0.8f && where != null) {
                float[] o = screen(mc, where, w, h);
                lines(m, o[0], o[1], w, h, new Random(seed), 30, a * 0.45f);
            }
        } else role = -1;
        // A heavy landed nearby, out of view: a soft glow on the edge it came from.
        long ct = Util.getMeasuringTimeMs() - cueAt;
        if (cueWhere != null && ct < 650) {
            float[] dir = offscreen(mc, cueWhere);
            if (dir != null) edgeGlow(m, w, h, dir[0], dir[1], argb((1 - ct / 650f) * 0.55f * cueStrength, 0xFFF1DC));
        }
    }

    private static int argb(float a, int rgb) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF);
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
        return new float[] {Math.max(0, Math.min(w, w / 2f + v.x / -v.z * focal)), Math.max(0, Math.min(h, h / 2f - v.y / -v.z * focal))};
    }

    /** If a point is out of view, the screen direction towards it (x right, y down, unit); null if it's in view. */
    private static float[] offscreen(MinecraftClient mc, Vec3d p) {
        Camera cam = mc.gameRenderer.getCamera();
        Vec3d rel = p.subtract(cam.getPos());
        Vector3f v = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        new Quaternionf(cam.getRotation()).conjugate().transform(v);
        double fov = Math.toRadians(mc.options.getFov().getValue());
        if (v.z < -0.1f) {
            double half = Math.tan(fov / 2);
            if (Math.abs(v.x / -v.z) < half * 1.4 && Math.abs(v.y / -v.z) < half) return null; // on screen: they can see it
        }
        float dx = v.x, dy = -v.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return new float[] {0, 1};
        return new float[] {dx / len, dy / len};
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

    /** A tint strongest at the screen's edges, fading to nothing a share of the way in. */
    private static void vignette(Matrix4f m, int w, int h, int edge, float inset) {
        int clear = edge & 0x00FFFFFF;
        float ix = w * inset, iy = h * inset;
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        // top, right, bottom, left: outer edge (tinted) to inner edge (clear)
        quad(b, m, 0, 0, w, 0, w - ix, iy, ix, iy, edge, clear);
        quad(b, m, w, 0, w, h, w - ix, h - iy, w - ix, iy, edge, clear);
        quad(b, m, w, h, 0, h, ix, h - iy, w - ix, h - iy, edge, clear);
        quad(b, m, 0, h, 0, 0, ix, iy, ix, h - iy, edge, clear);
        end(b);
    }

    private static void quad(BufferBuilder b, Matrix4f m, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int outer, int inner) {
        b.vertex(m, x1, y1, 0).color(outer);
        b.vertex(m, x2, y2, 0).color(outer);
        b.vertex(m, x3, y3, 0).color(inner);
        b.vertex(m, x4, y4, 0).color(inner);
    }

    /** A soft glow on the screen's edge in direction (dx, dy) from the middle. */
    private static void edgeGlow(Matrix4f m, int w, int h, float dx, float dy, int col) {
        float cx = w / 2f, cy = h / 2f;
        float sx = Math.abs(dx) < 1e-4f ? Float.MAX_VALUE : (w / 2f) / Math.abs(dx), sy = Math.abs(dy) < 1e-4f ? Float.MAX_VALUE : (h / 2f) / Math.abs(dy);
        float s = Math.min(sx, sy);
        float ex = cx + dx * s, ey = cy + dy * s;
        float tx = -dy, ty = dx, span = Math.min(w, h) * 0.22f, depth = Math.min(w, h) * 0.12f;
        int clear = col & 0x00FFFFFF;
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        // A fan: bright at the edge point, fading along the edge and inwards.
        float ix = ex - dx * depth, iy = ey - dy * depth;
        b.vertex(m, ex, ey, 0).color(col);
        b.vertex(m, ex + tx * span, ey + ty * span, 0).color(clear);
        b.vertex(m, ix, iy, 0).color(clear);
        b.vertex(m, ex, ey, 0).color(col);
        b.vertex(m, ix, iy, 0).color(clear);
        b.vertex(m, ex - tx * span, ey - ty * span, 0).color(clear);
        end(b);
    }

    /** Thin streaks from the edges towards a point, never reaching it. */
    private static void lines(Matrix4f m, float cx, float cy, int w, int h, Random r, int n, float k) {
        if (k <= 0.02f) return;
        begin();
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        float far = (float) Math.hypot(w, h);
        int col = ((int) (110 * Math.min(1, k)) << 24) | 0xFFFFFF;
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2, wd = 0.003 + r.nextDouble() * 0.006;
            float inner = far * (0.36f + r.nextFloat() * 0.2f);
            b.vertex(m, cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner, 0).color(col);
            b.vertex(m, cx + (float) Math.cos(a - wd) * far, cy + (float) Math.sin(a - wd) * far, 0).color(col & 0x00FFFFFF);
            b.vertex(m, cx + (float) Math.cos(a + wd) * far, cy + (float) Math.sin(a + wd) * far, 0).color(col & 0x00FFFFFF);
        }
        end(b);
    }
}
