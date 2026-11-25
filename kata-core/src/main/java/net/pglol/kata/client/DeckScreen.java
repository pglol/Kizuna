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
 * The combat deck. Left: your four stances laid out like the diamond (top stances above, back stances
 * below), each with its light chain (three slots, thrown in order on quick clicks) and its heavy (hold
 * to charge). Right: the style's moves. Pick a slot, then a move to put in it; right-click a slot to
 * empty it.
 */
public class DeckScreen extends Screen {
    private static final int PANEL = 0xD0101218, CARD = 0xC01A1D24, EDGE = 0x40FFFFFF, ACCENT = 0xFFE8D6C8, DIM = 0xFF8A8F9C, TEXT = 0xFFF2F2F5;
    private static final String[] NAMES = {"Top right", "Top left", "Back right", "Back left"};
    private static final String[] SUB = {"Chest to them", "Chest to them", "Back turned", "Back turned"};

    private int selStance = Fighting.TR, selSlot = 0;
    private int hover = -1;
    private final List<int[]> slotBoxes = new ArrayList<>(); // x, y, w, h, stance, slot
    private final List<int[]> moveBoxes = new ArrayList<>(); // x, y, w, h, move index
    private int[] resetBox, doneBox;

    public DeckScreen() {
        super(Text.literal("Combat deck"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        Style style = Deck.style();
        slotBoxes.clear();
        moveBoxes.clear();
        int w = width, h = height;
        int pw = Math.min(w - 20, 460), ph = Math.min(h - 20, 250);
        int x0 = (w - pw) / 2, y0 = (h - ph) / 2;
        c.fill(x0, y0, x0 + pw, y0 + ph, PANEL);
        outline(c, x0, y0, pw, ph, EDGE);

        // Header: the style and how to use the deck.
        c.drawText(textRenderer, style.name().toUpperCase(), x0 + 10, y0 + 9, ACCENT, false);
        c.drawText(textRenderer, style.blurb(), x0 + 12 + textRenderer.getWidth(style.name().toUpperCase()), y0 + 9, DIM, false);
        c.drawText(textRenderer, "Click: light chain  ·  Hold: heavy  ·  Drag mouse: change stance", x0 + 10, y0 + 21, DIM, false);

        // Left: the four stances.
        int gx = x0 + 10, gy = y0 + 36, cw = 120, ch = 92, gap = 6;
        int[][] at = {{1, 0}, {0, 0}, {1, 1}, {0, 1}}; // TR right-top, TL left-top, BR right-bottom, BL left-bottom
        for (int q = 0; q < 4; q++) {
            int cx = gx + at[q][0] * (cw + gap), cy = gy + at[q][1] * (ch + gap);
            boolean cur = q == Fight.stance();
            c.fill(cx, cy, cx + cw, cy + ch, CARD);
            outline(c, cx, cy, cw, ch, cur ? 0xA0E8D6C8 : EDGE);
            c.drawText(textRenderer, NAMES[q], cx + 6, cy + 5, TEXT, false);
            c.drawText(textRenderer, SUB[q], cx + 6, cy + 15, DIM, false);
            for (int i = 0; i < Fighting.SLOTS; i++) {
                boolean heavy = i == Fighting.HEAVY_SLOT;
                int sx = cx + 6, sy = cy + 27 + i * 15, sw = cw - 12, sh = 13;
                boolean sel = q == selStance && i == selSlot;
                boolean over = mx >= sx && mx < sx + sw && my >= sy && my < sy + sh;
                c.fill(sx, sy, sx + sw, sy + sh, sel ? 0x60E8D6C8 : over ? 0x30FFFFFF : 0x20FFFFFF);
                if (sel) outline(c, sx, sy, sw, sh, ACCENT);
                String tag = heavy ? "HOLD" : String.valueOf(i + 1);
                c.drawText(textRenderer, tag, sx + 3, sy + 3, heavy ? 0xFFE0A060 : DIM, false);
                Move m = style.move(Deck.slot(q, i));
                c.drawText(textRenderer, m == null ? "-" : m.name(), sx + (heavy ? 30 : 12), sy + 3, m == null ? DIM : TEXT, false);
                slotBoxes.add(new int[] {sx, sy, sw, sh, q, i});
            }
        }

        // Right: the style's moves that fit the chosen slot.
        int lx = gx + 2 * (cw + gap) + 4, ly = gy, lw = x0 + pw - 10 - lx;
        boolean heavySlot = selSlot == Fighting.HEAVY_SLOT;
        c.drawText(textRenderer, NAMES[selStance] + "  ·  " + (heavySlot ? "heavy" : "light " + (selSlot + 1)), lx, ly, ACCENT, false);
        int row = ly + 13;
        hover = -1;
        Move[] pool = style.pool();
        for (int i = 0; i < pool.length; i++) {
            Move m = pool[i];
            if (!m.fits(selStance, selSlot)) continue;
            int rh = 22;
            boolean over = mx >= lx && mx < lx + lw && my >= row && my < row + rh;
            boolean in = Deck.slot(selStance, selSlot) == i;
            c.fill(lx, row, lx + lw, row + rh - 2, in ? 0x50E8D6C8 : over ? 0x30FFFFFF : 0x18FFFFFF);
            c.drawText(textRenderer, m.name(), lx + 4, row + 2, TEXT, false);
            String info = (m.lead() ? "lead" : "rear") + " · " + m.path().name().toLowerCase() + " · " + Math.round(m.contact() * 1000) + " ms";
            c.drawText(textRenderer, info, lx + 4, row + 11, DIM, false);
            // Damage, as a short bar.
            int bw = Math.round(Math.min(1, m.damage() / 7f) * 40);
            c.fill(lx + lw - 46, row + 4, lx + lw - 6, row + 7, 0x40FFFFFF);
            c.fill(lx + lw - 46, row + 4, lx + lw - 46 + bw, row + 7, m.heavy() ? 0xFFE0A060 : 0xFFD8403E);
            if (over) hover = i;
            moveBoxes.add(new int[] {lx, row, lw, rh - 2, i});
            row += rh;
        }

        // Buttons.
        int by = y0 + ph - 18;
        resetBox = button(c, x0 + 10, by, "Reset to starter", mx, my);
        doneBox = button(c, x0 + pw - 10 - (textRenderer.getWidth("Done") + 16), by, "Done", mx, my);
        c.drawText(textRenderer, "Right-click a slot to empty it", x0 + 110, by + 4, DIM, false);
    }

    private int[] button(DrawContext c, int x, int y, String label, int mx, int my) {
        int bw = textRenderer.getWidth(label) + 16, bh = 14;
        boolean over = mx >= x && mx < x + bw && my >= y && my < y + bh;
        c.fill(x, y, x + bw, y + bh, over ? 0x50FFFFFF : 0x28FFFFFF);
        outline(c, x, y, bw, bh, EDGE);
        c.drawText(textRenderer, label, x + 8, y + 3, TEXT, false);
        return new int[] {x, y, bw, bh};
    }

    private static void outline(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    private static boolean in(int[] b, double mx, double my) {
        return b != null && mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (int[] b : slotBoxes) {
            if (!in(b, mx, my)) continue;
            if (button == 1) Deck.set(b[4], b[5], -1);
            else {
                selStance = b[4];
                selSlot = b[5];
            }
            return true;
        }
        for (int[] b : moveBoxes) {
            if (in(b, mx, my)) {
                Deck.set(selStance, selSlot, b[4]);
                // Move on to the next slot of the chain, for quick deck building.
                if (selSlot < Fighting.HEAVY_SLOT - 1) selSlot++;
                return true;
            }
        }
        if (in(resetBox, mx, my)) {
            Deck.reset();
            return true;
        }
        if (in(doneBox, mx, my)) {
            close();
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
