package net.pglol.kata.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * The combat deck, compact. Styles across the top. On the left, the stance diamond: pick a quadrant.
 * On the right, that quadrant's chain (three lights, then the heavy) and, below it, the style's moves
 * that fit the chosen slot, with pips for speed and power. Click a move to slot it; right-click a slot
 * to empty it.
 */
public class DeckScreen extends Screen {
    private static final int W = 268, H = 150;
    private static final String[] QUAD = {"TOP RIGHT", "TOP LEFT", "BACK RIGHT", "BACK LEFT"};

    private int selStance = Fight.stance(), selSlot = 0;
    private float open;
    private final List<int[]> boxes = new ArrayList<>(); // x, y, w, h, kind (0 stance, 1 slot, 2 move, 3 style, 4 reset), value

    public DeckScreen() {
        super(Text.literal("Combat deck"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        // A light veil only: the world stays visible behind the deck.
        c.fillGradient(0, 0, width, height, 0x60000000, 0x90000000);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        open = Math.min(1, open + delta * 0.25f);
        float e = 1 - (1 - open) * (1 - open);
        boxes.clear();
        Style style = Deck.style();
        int acc = Ui.accent();
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1 - e) * 8);
        Ui.panel(c, x0, y0, W, H);
        c.fill(x0 + 2, y0, x0 + 40, y0 + 1, acc); // a short accent stroke on the top edge

        // Styles (tabs) and reset.
        int tx = x0 + 10;
        for (Style s : Fighting.STYLES) {
            String n = Ui.caps(s.name());
            float tw = Ui.width(textRenderer, n, 0.75f);
            boolean on = s == style;
            Ui.text(c, textRenderer, n, tx, y0 + 8, 0.75f, on ? Ui.TEXT : Ui.FAINT);
            if (on) c.fill(tx, y0 + 16, tx + Math.round(tw), y0 + 17, acc);
            boxes.add(new int[] {tx - 2, y0 + 4, Math.round(tw) + 4, 14, 3, indexOf(s)});
            tx += Math.round(tw) + 12;
        }
        String reset = "RESET";
        float rw = Ui.width(textRenderer, reset, 0.6f);
        int rx = x0 + W - 10 - Math.round(rw);
        boolean overReset = in(mx, my, rx - 3, y0 + 5, Math.round(rw) + 6, 12);
        Ui.text(c, textRenderer, reset, rx, y0 + 9, 0.6f, overReset ? Ui.TEXT : Ui.FAINT);
        boxes.add(new int[] {rx - 3, y0 + 5, Math.round(rw) + 6, 12, 4, 0});
        c.fill(x0 + 8, y0 + 22, x0 + W - 8, y0 + 23, Ui.EDGE);

        // Stance diamond.
        float cx = x0 + 52, cy = y0 + 72, r = 34;
        Ui.diamond(c, cx, cy, r, Fight.stance(), selStance, 1);
        // Quadrant hit areas (each quadrant's half-square).
        int[][] qa = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int q = 0; q < 4; q++) {
            int bx = Math.round(cx + (qa[q][0] > 0 ? 0 : -r)), by = Math.round(cy + (qa[q][1] > 0 ? 0 : -r));
            boxes.add(new int[] {bx, by, Math.round(r), Math.round(r), 0, q});
        }
        Ui.text(c, textRenderer, QUAD[selStance], cx - Ui.width(textRenderer, QUAD[selStance], 0.6f) / 2, cy + r + 8, 0.6f, Ui.SOFT);

        // The chain: three lights, then the heavy.
        int px = x0 + 104, py = y0 + 30, cw = 36, ch = 26, gap = 4;
        for (int i = 0; i < Fighting.SLOTS; i++) {
            boolean heavy = i == Fighting.HEAVY_SLOT;
            int sx = px + i * (cw + gap) + (heavy ? 6 : 0);
            boolean sel = i == selSlot, over = in(mx, my, sx, py, cw, ch);
            c.fillGradient(sx, py, sx + cw, py + ch, sel ? 0x40FFFFFF : over ? 0x26FFFFFF : 0x14FFFFFF, sel ? 0x20FFFFFF : 0x08FFFFFF);
            Ui.edge(c, sx, py, cw, ch, sel ? (heavy ? Ui.HEAVY : acc) : Ui.EDGE);
            c.fill(sx + 3, py + ch - 3, sx + cw - 3, py + ch - 2, heavy ? Ui.alpha(Ui.HEAVY, 0.7f) : Ui.alpha(acc, 0.5f));
            Ui.text(c, textRenderer, heavy ? "HOLD" : String.valueOf(i + 1), sx + 3, py + 3, 0.5f, heavy ? Ui.HEAVY : Ui.FAINT);
            Move m = style.move(Deck.slot(selStance, i));
            String name = m == null ? "—" : m.name();
            drawWrapped(c, name, sx + 3, py + 9, cw - 6, m == null ? Ui.FAINT : Ui.TEXT);
            boxes.add(new int[] {sx, py, cw, ch, 1, i});
            if (i < Fighting.HEAVY_SLOT - 1) Ui.text(c, textRenderer, "›", sx + cw + 0.5f, py + 9, 0.75f, Ui.FAINT);
        }

        // The moves that fit the chosen slot.
        int ly = py + ch + 8, lx = px, lw = x0 + W - 10 - lx;
        Ui.text(c, textRenderer, "SPEED", lx + lw - 50, ly - 1, 0.45f, Ui.FAINT);
        Ui.text(c, textRenderer, "POWER", lx + lw - 23, ly - 1, 0.45f, Ui.FAINT);
        ly += 5;
        Move[] pool = style.pool();
        for (int i = 0; i < pool.length; i++) {
            Move m = pool[i];
            if (!m.fits(selStance, selSlot)) continue;
            if (ly + 11 > y0 + H - 6) break;
            boolean in = Deck.slot(selStance, selSlot) == i, over = in(mx, my, lx, ly, lw, 11);
            if (in || over) c.fill(lx, ly, lx + lw, ly + 11, in ? Ui.alpha(acc, 0.22f) : 0x18FFFFFF);
            if (in) c.fill(lx, ly, lx + 1, ly + 11, acc);
            Ui.text(c, textRenderer, m.name(), lx + 4, ly + 2.5f, 0.75f, in ? Ui.TEXT : Ui.SOFT);
            Ui.text(c, textRenderer, (m.lead() ? "LEAD" : "REAR"), lx + 4 + Ui.width(textRenderer, m.name(), 0.75f) + 4, ly + 3.5f, 0.45f, Ui.FAINT);
            int speed = Math.max(1, Math.min(5, Math.round(6 - m.contact() * 30)));
            int power = Math.max(1, Math.min(5, Math.round(m.damage() / 1.4f)));
            Ui.pips(c, lx + lw - 50, ly + 5, speed, 5, Ui.TEXT);
            Ui.pips(c, lx + lw - 23, ly + 5, power, 5, m.heavy() ? Ui.HEAVY : acc);
            boxes.add(new int[] {lx, ly, lw, 11, 2, i});
            ly += 12;
        }
    }

    /** A move name in a slot chip: small, on up to two lines. */
    private void drawWrapped(DrawContext c, String s, int x, int y, int w, int col) {
        float sc = 0.6f;
        if (Ui.width(textRenderer, s, sc) <= w) {
            Ui.text(c, textRenderer, s, x, y + 3, sc, col);
            return;
        }
        int cut = s.lastIndexOf(' ');
        if (cut <= 0) {
            Ui.text(c, textRenderer, s, x, y + 3, 0.5f, col);
            return;
        }
        Ui.text(c, textRenderer, s.substring(0, cut), x, y, sc, col);
        Ui.text(c, textRenderer, s.substring(cut + 1), x, y + 6, sc, col);
    }

    private static int indexOf(Style s) {
        for (int i = 0; i < Fighting.STYLES.length; i++) if (Fighting.STYLES[i] == s) return i;
        return 0;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int[] b : boxes) {
            if (!in(mx, my, b[0], b[1], b[2], b[3])) continue;
            switch (b[4]) {
                case 0 -> {
                    selStance = b[5];
                    if (selSlot >= Fighting.SLOTS) selSlot = 0;
                }
                case 1 -> {
                    if (button == 1) Deck.set(selStance, b[5], -1);
                    else selSlot = b[5];
                }
                case 2 -> {
                    Deck.set(selStance, selSlot, b[5]);
                    if (selSlot < Fighting.HEAVY_SLOT - 1) selSlot++; // on to the next link of the chain
                }
                case 3 -> Deck.use(Fighting.STYLES[b[5]]);
                case 4 -> Deck.reset();
                default -> { }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (KataClient.deckKey.matchesKey(key, scan)) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
