package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * The HUD, kept to the edges and out of the way.
 *  - Bottom centre: health, a red brush stroke in black ink (flames off its end, ink dripping), a pale
 *    trail of what you just lost catching up a moment
 *    later; stamina, a hairline above it that dims when you've run yourself dry.
 *  - Bottom left: the fight tag. A small stance diamond and your style; squared up to someone, what a
 *    click and a hold will throw, with the heavy's charge.
 */
public final class Hud {
    private Hud() {}

    private static float trail = -1, shown = -1, engage, letterbox;
    /** A word that pops up under the crosshair for a moment (PARRY, EVADE...). */
    private static String popText;
    private static int popColor;
    private static long popAt;

    public static void pop(String text, int color) {
        popText = text;
        popColor = color;
        popAt = System.nanoTime();
    }
    private static long last;

    static void render(DrawContext c, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || p.isSpectator() || mc.options.hudHidden) return;
        long now = System.nanoTime();
        float dt = last == 0 ? 0 : MathHelper.clamp((now - last) / 1e9f, 0, 0.1f);
        last = now;

        float hp = MathHelper.clamp(p.getHealth() / Math.max(1, p.getMaxHealth()), 0, 1);
        if (shown < 0) shown = trail = hp;
        shown += (hp - shown) * (1 - (float) Math.exp(-dt / 0.06f));
        if (hp >= trail) trail = hp;
        else trail = Math.max(hp, trail - dt * 0.5f);
        Move.shownStamina += (Move.stamina - Move.shownStamina) * (1 - (float) Math.exp(-dt / 0.08f));

        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        // Squared up to someone: black bars close in top and bottom like a film, and the HUD slides up
        // out of the bottom one. They open again when you let them go.
        letterbox += ((Fight.locked() ? 1 : 0) - letterbox) * (1 - (float) Math.exp(-dt / 0.18f));
        if (letterbox < 0.002f) letterbox = 0;
        float lb = letterbox * letterbox * (3 - 2 * letterbox);
        int bar = Math.round(h * 0.085f * lb);
        if (bar > 0) {
            c.fill(0, 0, w, bar, 0xFF000000);
            c.fill(0, h - bar, w, h, 0xFF000000);
        }
        c.getMatrices().push();
        c.getMatrices().translate(0, -(h * 0.085f + 4) * lb, 0);
        int hw = 160, sw = 104;
        int hx = (w - hw) / 2, hy = h - 14;
        int sx = hx + 4, sy = hy - 9; // under the start of the health stroke, clear of its flames
        float time = (now % 1_000_000_000_000L) / 1e9f;

        // Health: red in a ragged black ink stroke, flames licking off its end, ink dripping beneath.
        boolean low = hp < 0.25f;
        int col = low ? (((now / 250_000_000L) & 1) == 0 ? 0xFFE8383C : 0xFFB02A2D) : 0xFFD8302E;
        inkBar(c, hx, hy, hw, 5, MathHelper.clamp(shown, 0, 1), trail, col, 0xC0F0E2D6, time, 1f, 0, dt, low ? 2.5f : 1f);

        // Stamina: the same ink, finer, a pale breath colour; dim when you're winded.
        int stam = Move.winded() ? 0xFF8C8268 : 0xFFF3E7BE;
        inkBar(c, sx, sy, sw, 2, MathHelper.clamp(Move.shownStamina, 0, 1), -1, stam, 0, time, 0.45f, 1, dt, 0.35f);

        tag(c, mc, h, dt);
        c.getMatrices().pop();
        // The pop: in with a little punch, then fading up and away.
        if (popText != null) {
            float s = (now - popAt) / 1e9f;
            if (s < 0.8f) {
                float a = s < 0.6f ? 1 : 1 - (s - 0.6f) / 0.2f;
                float sc = 1.25f - 0.25f * Math.min(1, s / 0.08f);
                float tw = Ui.width(mc.textRenderer, popText, sc);
                Ui.text(c, mc.textRenderer, popText, (w - tw) / 2f, h / 2f + 14 - s * 6, sc, Ui.alpha(popColor, a));
            } else popText = null;
        }
    }

    /** A drop of ink: hanging from the bar's underside and stretching, then falling and fading. */
    private static final class Drip {
        float x, len, y, vy, life;
        boolean falling;
        int bar;
    }

    private static final java.util.List<Drip> DRIPS = new java.util.ArrayList<>();
    private static final float[] DRIP_CLOCK = new float[2];
    private static final int INK = 0xF0080808;

    private static int brighter(int col) {
        int r = Math.min(255, ((col >> 16) & 0xFF) + 28), g = Math.min(255, ((col >> 8) & 0xFF) + 22), b = Math.min(255, (col & 0xFF) + 18);
        return (col & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Repeatable noise in 0..1 for an integer (the ink's ragged edge stays put frame to frame). */
    private static float noise(int i, int seed) {
        double v = Math.sin(i * 12.9898 + seed * 78.233) * 43758.5453;
        return (float) (v - Math.floor(v));
    }

    /** Smooth noise along the bar: the edge wanders rather than jitters. */
    private static float wander(float x, int seed) {
        int i = (int) Math.floor(x);
        float f = x - i, k = f * f * (3 - 2 * f);
        return noise(i, seed) * (1 - k) + noise(i + 1, seed) * k;
    }

    /**
     * A brush-stroke bar. The fill is slanted at both ends (the top runs further than the bottom), mottled
     * darker inside; the ink stroke around it has a ragged, wandering edge, fatter underneath. Off its end
     * black flames rise, curling and flickering, and now and then shed an ember; under it ink beads, hangs,
     * stretches and drips away. `flame` scales the flames, `drip` how often it drips.
     */
    private static void inkBar(DrawContext c, int x, int y, int w, int h, float fill, float trail, int col, int trailCol,
                               float time, float flame, int bar, float dt, float drip) {
        int slant = Math.max(1, h);
        // The ink stroke.
        for (int i = -3; i <= w + 4; i++) {
            float end = i < 0 ? (i + 3) / 3f : i > w ? 1 - (i - w) / 5f : 1; // tapering at both ends
            int top = Math.round(1 + wander(i / 6f, 3 + bar) * 1.8f * end);
            int bot = Math.round(1 + wander(i / 5f, 7 + bar) * 2.6f * end + (noise(i, 11 + bar) > 0.93f ? 1 : 0));
            if (end <= 0) continue;
            c.fill(x + i, y - top, x + i + 1, y + h + bot, INK);
        }
        // The empty track (a dim stain of the colour), the loss trail, then the fill, row by row so the ends slant.
        for (int r = 0; r < h; r++) {
            int l = x + 1 + r * slant / h, rgt = r * slant * 2 / h;
            if (x + w - rgt > l) c.fill(l, y + r, x + w - rgt, y + r + 1, Ui.alpha(Ui.darker(col, 0.35f), 0.85f));
            if (trail > fill) {
                int te = x + Math.round(w * trail) - rgt;
                if (te > l) c.fill(l, y + r, te, y + r + 1, trailCol);
            }
            int fe = x + Math.round(w * fill) - rgt;
            if (fe > l) c.fill(l, y + r, fe, y + r + 1, r == 0 ? brighter(col) : r >= h - 1 && h > 2 ? Ui.darker(col, 0.72f) : col);
        }
        // Mottling: darker flecks through the paint, fixed in place.
        if (h > 2) {
            int fe = x + Math.round(w * fill);
            for (int i = 2; i < w; i += 2) {
                if (x + i >= fe - 2) break;
                float n = noise(i, 21 + bar);
                if (n > 0.62f) c.fill(x + i, y + 1 + (int) (n * 10) % (h - 1), x + i + (n > 0.85f ? 2 : 1), y + 2 + (int) (n * 10) % (h - 1), Ui.alpha(Ui.darker(col, 0.6f), 0.55f));
            }
        }
        // Flames off the end: tendrils rising from the top edge, the tallest at the end, swaying.
        if (flame > 0) {
            float[][] tongues = {{-40, 4}, {-28, 6}, {-17, 10}, {-7, 15}, {1, 17}, {5, 9}};
            for (int k = 0; k < tongues.length; k++) {
                float base = x + w + tongues[k][0];
                float tall = tongues[k][1] * flame * (0.8f + 0.2f * MathHelper.sin(time * (5 + k) + k * 1.7f));
                int n = Math.round(tall);
                for (int j = 0; j < n; j++) {
                    float up = j / Math.max(1f, tall);
                    // Leaning back along the bar, curling at the tip.
                    float sway = MathHelper.sin(time * 3.2f + k * 1.3f - j * 0.45f) * up * 1.6f - up * up * 2.2f;
                    int px = Math.round(base + sway);
                    int wid = up < 0.25f ? 3 : up < 0.6f ? 2 : 1;
                    c.fill(px, y - 2 - j, px + wid, y - 1 - j, INK);
                }
                // A curl at the tip.
                if (n > 4) {
                    float sway = MathHelper.sin(time * 3.2f + k * 1.3f - n * 0.45f) * 1.6f - 2.2f;
                    int px = Math.round(base + sway);
                    c.fill(px + 1, y - 1 - n, px + 2, y - n, INK);
                }
            }
            // Embers: a speck leaves a flame now and then, drifting up and fading.
            for (int k = 0; k < 3; k++) {
                float ph = (time * 0.7f + k * 0.37f) % 1f;
                float ex = x + w - 14 + k * 7 + MathHelper.sin(time * 2 + k) * 2, ey = y - 6 - ph * 14 * flame;
                c.fill(Math.round(ex), Math.round(ey), Math.round(ex) + 1, Math.round(ey) + 1, Ui.alpha(INK, (1 - ph) * 0.8f));
            }
        }
        // Drips: a bead forms under the stroke (more towards the end), stretches, lets go and falls.
        DRIP_CLOCK[bar] -= dt * drip;
        if (DRIP_CLOCK[bar] <= 0 && dt > 0) {
            DRIP_CLOCK[bar] = 0.5f + noise((int) (time * 100), 5) * 0.9f;
            Drip d = new Drip();
            float r = noise((int) (time * 1000), 9 + bar);
            d.x = x + w * (0.15f + 0.85f * (float) Math.sqrt(r));
            d.bar = bar;
            DRIPS.add(d);
        }
        int under = y + h + 2;
        for (java.util.Iterator<Drip> it = DRIPS.iterator(); it.hasNext(); ) {
            Drip d = it.next();
            if (d.bar != bar) continue;
            d.life += dt;
            int dx = Math.round(d.x);
            if (!d.falling) {
                d.len = Math.min(4, d.len + dt * 5);
                int L = Math.round(d.len);
                c.fill(dx, under, dx + 1, under + L, INK);
                c.fill(dx - (L > 2 ? 1 : 0), under + L, dx + 1, under + L + 1, INK); // the bead
                if (d.len >= 4 && d.life > 1.1f) {
                    d.falling = true;
                    d.y = under + L;
                }
            } else {
                d.vy += dt * 60;
                d.y += d.vy * dt;
                float a = Math.max(0, 1 - (d.y - under) / 22f);
                if (a <= 0) {
                    it.remove();
                    continue;
                }
                int dy = Math.round(d.y);
                c.fill(dx, dy, dx + 1, dy + 2, Ui.alpha(INK, a));
                c.fill(dx, under, dx + 1, under + 1, INK); // what's left at the bar
            }
        }
        if (DRIPS.size() > 40) DRIPS.remove(0);
    }

    private static void track(DrawContext c, int x, int y, int w, int h) {
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x80000000);
        c.fill(x, y, x + w, y + h, 0x50262A33);
    }

    /** The fight tag, bottom left. */
    private static void tag(DrawContext c, MinecraftClient mc, int h, float dt) {
        var tr = mc.textRenderer;
        boolean fighting = Fight.locked() || Fight.chargingHeavy();
        engage += ((fighting ? 1 : 0) - engage) * (1 - (float) Math.exp(-dt * 10));
        int x = 8, y = h - 26;
        int acc = Ui.accent();
        // The diamond and the style.
        Ui.diamond(c, x + 9, y + 9, 8, Fight.stance(), -1, 0.95f);
        String name = Ui.caps(Deck.style().name());
        Ui.text(c, tr, name, x + 22, y + 4, 0.6f, Ui.TEXT);
        c.fill(x + 22, y + 11, x + 22 + 14, y + 12, acc);
        if (engage < 0.02f) return;

        // What a click and a hold will throw.
        net.pglol.kata.Fighting.Move light = Fight.nextLight(), heavy = Fight.nextHeavy();
        float a = engage;
        int rowY = y - 22;
        chip(c, tr, x, rowY, "TAP", light, Ui.alpha(Ui.TEXT, a), Ui.alpha(acc, a), a);
        chip(c, tr, x, rowY + 10, "HOLD", heavy, Ui.alpha(Ui.TEXT, a), Ui.alpha(Ui.HEAVY, a), a);
        if (Fight.chargingHeavy()) {
            float k = Fight.charge();
            int bw = 70;
            c.fill(x, rowY + 19, x + bw, rowY + 20, Ui.alpha(0xFF000000, 0.5f * a));
            c.fill(x, rowY + 19, x + Math.round(bw * k), rowY + 20, k >= 1 ? 0xFFFFE0A0 : Ui.HEAVY);
        }
    }

    /** A key and what it throws: the key, the move's icon, its name. */
    private static void chip(DrawContext c, net.minecraft.client.font.TextRenderer tr, int x, int y, String key, net.pglol.kata.Fighting.Move move, int textCol, int keyCol, float a) {
        float kw = Ui.width(tr, key, 0.5f);
        c.fill(x, y, x + Math.round(kw) + 5, y + 7, Ui.alpha(0xFF000000, 0.45f * a));
        c.fill(x, y + 7, x + Math.round(kw) + 5, y + 8, keyCol);
        Ui.text(c, tr, key, x + 2.5f, y + 1.5f, 0.5f, keyCol);
        int ix = x + Math.round(kw) + 8;
        if (move != null) Icons.draw(c, move, ix, y - 1, 1, Ui.alpha(0xFF0E0C0A, a), keyCol);
        Ui.text(c, tr, move == null ? "—" : move.name(), ix + 13, y + 0.5f, 0.7f, textCol);
    }
}
