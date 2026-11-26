package net.pglol.kata.client;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.pglol.kata.Fighting;
import org.joml.Matrix4f;

/**
 * Kata's look: dark glass panels with hairline edges and clipped corners, one accent colour per style,
 * small type, and the faceted stance diamond. Shared by the HUD and the screens.
 */
public final class Ui {
    private Ui() {}

    // The dojo palette: warm ink panels, paper-coloured type, a vermilion mark, gold for heavies.
    public static final int GLASS_TOP = 0xF2141210, GLASS_BOTTOM = 0xF2141210, EDGE = 0x22EDE5D3, EDGE_LIT = 0xFFD9472B;
    public static final int TEXT = 0xFFEDE5D3, SOFT = 0xFFA79F8E, FAINT = 0xFF655F55, HEAVY = 0xFFD9A55B;

    public static int accent() {
        return 0xFF000000 | Deck.style().color();
    }

    public static int alpha(int col, float a) {
        return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (col & 0xFFFFFF);
    }

    /** A panel: flat warm ink, a hairline edge, one short vermilion mark at the top left. */
    public static void panel(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, GLASS_TOP);
        c.fill(x, y, x + w, y + 1, EDGE);
        c.fill(x, y + h - 1, x + w, y + h, EDGE);
        c.fill(x, y, x + 1, y + h, EDGE);
        c.fill(x + w - 1, y, x + w, y + h, EDGE);
        c.fill(x, y, x + Math.min(24, w), y + 1, EDGE_LIT);
    }

    /** A hairline outline with clipped corners. */
    public static void edge(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x + 2, y, x + w - 2, y + 1, col);
        c.fill(x + 2, y + h - 1, x + w - 2, y + h, col);
        c.fill(x, y + 2, x + 1, y + h - 2, col);
        c.fill(x + w - 1, y + 2, x + w, y + h - 2, col);
        c.fill(x + 1, y + 1, x + 2, y + 2, col);
        c.fill(x + w - 2, y + 1, x + w - 1, y + 2, col);
        c.fill(x + 1, y + h - 2, x + 2, y + h - 1, col);
        c.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, col);
    }

    /** Text at a scale (small labels and captions). */
    public static void text(DrawContext c, TextRenderer tr, String s, float x, float y, float scale, int col) {
        c.getMatrices().push();
        c.getMatrices().translate(x, y, 0);
        c.getMatrices().scale(scale, scale, 1);
        c.drawText(tr, s, 0, 0, col, false);
        c.getMatrices().pop();
    }

    public static float width(TextRenderer tr, String s, float scale) {
        return tr.getWidth(s) * scale;
    }

    /** Spaced capitals, for titles. */
    public static String caps(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toUpperCase().toCharArray()) b.append(ch).append(' ');
        return b.toString().trim();
    }

    /** A filled triangle. */
    public static void tri(DrawContext c, float x1, float y1, float x2, float y2, float x3, float y3, int col) {
        // Same winding as Minecraft's own quads, so it isn't culled.
        if ((x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1) > 0) {
            float tx = x2, ty = y2;
            x2 = x3;
            y2 = y3;
            x3 = tx;
            y3 = ty;
        }
        Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        VertexConsumer vc = c.getVertexConsumers().getBuffer(RenderLayer.getGui());
        vc.vertex(m, x1, y1, 0).color(col);
        vc.vertex(m, x2, y2, 0).color(col);
        vc.vertex(m, x3, y3, 0).color(col);
        vc.vertex(m, x3, y3, 0).color(col);
        c.draw();
    }

    /**
     * The stance diamond (as at your feet): four faceted quadrants, the top towards the opponent; the
     * active one in the accent colour, the selected one (if any, -1 for none) outlined bright.
     */
    public static void diamond(DrawContext c, float cx, float cy, float r, int active, int selected, float alpha) {
        float[][] tips = {{0, -r}, {r, 0}, {0, r}, {-r, 0}}; // front, right, back, left
        int[][] quads = {{0, 1}, {0, 3}, {2, 1}, {2, 3}}; // TR, TL, BR, BL: {facing tip, side tip}
        int acc = accent();
        for (int q = 0; q < 4; q++) {
            float[] a = tips[quads[q][0]], b = tips[quads[q][1]];
            float mx = (a[0] + b[0]) / 2, my = (a[1] + b[1]) / 2;
            boolean on = q == active;
            int lit = on ? acc : 0xFFE9ECF2, shade = on ? darker(acc, 0.7f) : 0xFFB7BCC8;
            if (!on && q == selected) {
                lit = 0xFFFFFFFF;
                shade = 0xFFD5D9E2;
            }
            // Two facets give each blade its raised, folded look.
            tri(c, cx, cy, cx + a[0], cy + a[1], cx + mx, cy + my, alpha(lit, alpha * (on ? 1 : 0.85f)));
            tri(c, cx, cy, cx + mx, cy + my, cx + b[0], cy + b[1], alpha(shade, alpha * (on ? 1 : 0.85f)));
        }
        // The small blades between them.
        float k = r * 0.62f, s = r * 0.16f;
        for (int[] d : new int[][] {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}}) {
            float dx = d[0] * 0.7071f, dy = d[1] * 0.7071f;
            float ix = cx + dx * r * 0.36f, iy = cy + dy * r * 0.36f, ox = cx + dx * k * 1.25f, oy = cy + dy * k * 1.25f;
            float px = -dy * s, py = dx * s, mx = cx + dx * k * 0.85f, my = cy + dy * k * 0.85f;
            tri(c, ix, iy, mx + px, my + py, ox, oy, alpha(0xFFD9DDE6, alpha * 0.55f));
            tri(c, ix, iy, ox, oy, mx - px, my - py, alpha(0xFF9FA5B3, alpha * 0.55f));
        }
    }

    public static int darker(int col, float k) {
        int r = (int) (((col >> 16) & 0xFF) * k), g = (int) (((col >> 8) & 0xFF) * k), b = (int) ((col & 0xFF) * k);
        return (col & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Pips: n of max filled. */
    public static void pips(DrawContext c, int x, int y, int n, int max, int col) {
        for (int i = 0; i < max; i++) c.fill(x + i * 4, y, x + i * 4 + 3, y + 2, i < n ? col : 0x30FFFFFF);
    }

    /** Which stance a quadrant index is (the diamond's order). */
    public static boolean top(int q) {
        return Fighting.top(q);
    }
}
