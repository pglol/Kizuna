package net.pglol.kata.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Style;

import java.util.ArrayList;
import java.util.List;

/**
 * The combat deck. Quiet and flat: warm ink, paper-coloured type, one vermilion accent (a dojo, not a
 * casino).
 *  - Left: the styles you know (operators: all of them).
 *  - Top: the stance diamond (pick a quadrant, or the air) and that quadrant's chain: three lights in
 *    order, then the heavy. Each slot shows its move's icon.
 *  - Top right: the selected move, thrown on your own body, with its name and speed and power.
 *  - Below: the moves you've learned in the selected style, as icons, each with its style at the top.
 *    Those that fit the selected slot are bright.
 * Drag a move onto a slot to put it there; drag one slot onto another to swap them; drag a slot off the
 * chain to empty it. Click anything to preview it. Right-click a move to drop it in the selected slot.
 */
public class DeckScreen extends Screen {
    private static final String[] QUAD = {"TOP RIGHT", "TOP LEFT", "BACK RIGHT", "BACK LEFT", "IN THE AIR"};
    // The palette: ink, paper, one accent.
    private static final int BG = 0xF2141210, SIDE = 0xF20E0D0B, LINE = 0x22EDE5D3;
    private static final int PAPER = 0xFFEDE5D3, PAPER_SOFT = 0xFFA79F8E, PAPER_FAINT = 0xFF655F55;
    private static final int VERMILION = 0xFFD9472B, GOLD = 0xFFD9A55B;
    private static final int SLOT = 30, TILE_W = 32, TILE_H = 38;

    private Style selStyle = Deck.style();
    private int selStance = Math.min(Fight.stance(), 3), selSlot = 0, scroll;
    private Move selMove;
    private float open;
    // Hit areas, rebuilt each frame: x, y, w, h, kind, value.
    private static final int K_STYLE = 0, K_QUAD = 1, K_SLOT = 2, K_TILE = 3, K_RESET = 4;
    private final List<int[]> boxes = new ArrayList<>();
    // Dragging: what (a global move number), from where (a slot, or -1 for the list), and whether it has moved yet.
    private int dragId = -1, dragFrom = -1;
    private double pressX, pressY;
    private boolean dragging;
    private int rows;

    public DeckScreen() {
        super(Text.literal("Combat deck"));
        selMove = Deck.pick(selStance, false, 0);
        Fight.previewStart = System.nanoTime();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0x88000000);
    }

    private static int accentOf(Style s) {
        return 0xFF000000 | s.color();
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        open = Math.min(1, open + delta * 0.2f);
        float e = 1 - (1 - open) * (1 - open);
        boxes.clear();
        int W = Math.min(width - 24, 420), H = Math.min(height - 24, 250);
        int x0 = (width - W) / 2, y0 = (height - H) / 2 + Math.round((1 - e) * 6);
        int sw = 86;
        c.fill(x0, y0, x0 + W, y0 + H, BG);
        c.fill(x0, y0, x0 + sw, y0 + H, SIDE);
        c.fill(x0 + sw, y0, x0 + sw + 1, y0 + H, LINE);
        c.fill(x0, y0, x0 + 24, y0 + 1, VERMILION); // the one mark of colour on the frame

        // ---- Styles ----
        Ui.text(c, textRenderer, "STYLES", x0 + 10, y0 + 10, 0.55f, PAPER_FAINT);
        int sy = y0 + 22;
        for (Style s : Deck.known()) {
            boolean on = s == selStyle, over = in(mx, my, x0, sy, sw, 16);
            if (on || over) c.fill(x0, sy, x0 + sw, sy + 16, on ? 0x18EDE5D3 : 0x0CEDE5D3);
            if (on) c.fill(x0, sy, x0 + 2, sy + 16, accentOf(s));
            c.fill(x0 + 10, sy + 6, x0 + 14, sy + 10, accentOf(s));
            Ui.text(c, textRenderer, Ui.caps(s.name()), x0 + 19, sy + 5, 0.6f, on ? PAPER : PAPER_SOFT);
            boxes.add(new int[] {x0, sy, sw, 16, K_STYLE, indexOf(s)});
            sy += 17;
        }
        if (Deck.op()) Ui.text(c, textRenderer, "OPERATOR: ALL STYLES", x0 + 10, y0 + H - 28, 0.4f, PAPER_FAINT);
        boolean overReset = in(mx, my, x0 + 6, y0 + H - 18, sw - 12, 12);
        Ui.text(c, textRenderer, "RESET DECK", x0 + 10, y0 + H - 15, 0.5f, overReset ? PAPER : PAPER_FAINT);
        boxes.add(new int[] {x0 + 6, y0 + H - 18, sw - 12, 12, K_RESET, 0});

        int mx0 = x0 + sw + 1, mw = W - sw - 1;
        int top = 104;

        // ---- Stance diamond and the air ----
        float cx = mx0 + 44, cy = y0 + 42, r = 26;
        Ui.diamond(c, cx, cy, r, Fight.stance(), selStance < 4 ? selStance : -1, selStance == Fighting.AIR ? 0.45f : 1);
        int[][] qa = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int q = 0; q < 4; q++) {
            int bx = Math.round(cx + (qa[q][0] > 0 ? 0 : -r)), by = Math.round(cy + (qa[q][1] > 0 ? 0 : -r));
            boxes.add(new int[] {bx, by, Math.round(r), Math.round(r), K_QUAD, q});
        }
        int aw = 30, ah = 11, ax = Math.round(cx - aw / 2f), ay = Math.round(cy + r + 8);
        boolean airSel = selStance == Fighting.AIR;
        c.fill(ax, ay, ax + aw, ay + ah, airSel ? VERMILION : in(mx, my, ax, ay, aw, ah) ? 0x22EDE5D3 : 0x10EDE5D3);
        Ui.text(c, textRenderer, "AIR", ax + (aw - Ui.width(textRenderer, "AIR", 0.55f)) / 2, ay + 3, 0.55f, airSel ? 0xFF140E0C : PAPER_SOFT);
        boxes.add(new int[] {ax, ay, aw, ah, K_QUAD, Fighting.AIR});

        // ---- The chain ----
        int chx = mx0 + 96, chy = y0 + 30;
        Ui.text(c, textRenderer, QUAD[selStance], chx, y0 + 12, 0.6f, PAPER);
        Ui.text(c, textRenderer, "LIGHTS IN ORDER, THEN HOLD", chx, y0 + 20, 0.4f, PAPER_FAINT);
        for (int i = 0; i < Fighting.SLOTS; i++) {
            boolean heavy = i == Fighting.HEAVY_SLOT;
            int bx = chx + i * (SLOT + 5) + (heavy ? 7 : 0);
            boolean sel = i == selSlot, over = in(mx, my, bx, chy, SLOT, SLOT);
            boolean target = dragging && over && (Deck.move(dragId) == null || Deck.move(dragId).fits(selStance, i));
            c.fill(bx, chy, bx + SLOT, chy + SLOT, target ? 0x30D9472B : over ? 0x14EDE5D3 : 0x0AEDE5D3);
            Ui.edge(c, bx, chy, SLOT, SLOT, sel ? (heavy ? GOLD : VERMILION) : LINE);
            Move m = Deck.move(Deck.slot(selStance, i));
            if (m != null && !(dragging && dragFrom == i)) {
                Icons.draw(c, m, bx + 5, chy + 5, 2, 0xFF0E0C0A, heavy ? GOLD : accentOf(Fighting.styleOf(m)));
            }
            Ui.text(c, textRenderer, heavy ? "HOLD" : String.valueOf(i + 1), bx, chy + SLOT + 3, 0.45f, heavy ? GOLD : PAPER_FAINT);
            if (i < Fighting.HEAVY_SLOT - 1) c.fill(bx + SLOT + 1, chy + SLOT / 2, bx + SLOT + 4, chy + SLOT / 2 + 1, PAPER_FAINT);
            boxes.add(new int[] {bx, chy, SLOT, SLOT, K_SLOT, i});
        }

        // ---- Preview ----
        int pw = 86, px = x0 + W - pw - 8, py = y0 + 8, ph = top - 18;
        c.fill(px, py, px + pw, py + ph, 0x0AEDE5D3);
        if (selMove != null && client != null && client.player != null) {
            Fight.previewMove = selMove;
            Fight.previewStance = selStance < 4 ? selStance : Fight.stance();
            Fight.previewing = true;
            try {
                // Seen from a little to the side so a strike reads.
                InventoryScreen.drawEntity(c, px, py, px + pw, py + ph - 14, 26, 0.0625f, px + pw * 0.5f + 60, py + ph * 0.4f, client.player);
            } finally {
                Fight.previewing = false;
            }
            Ui.text(c, textRenderer, selMove.name(), px + 3, py + ph - 12, 0.55f, PAPER);
            int speed = Math.max(1, Math.min(5, Math.round(6 - selMove.contact() * 12)));
            int power = Math.max(1, Math.min(5, Math.round(selMove.damage() / 1.4f)));
            Ui.pips(c, px + 3, py + ph - 4, speed, 5, PAPER_SOFT);
            Ui.pips(c, px + 26, py + ph - 4, power, 5, selMove.heavy() ? GOLD : VERMILION);
        }

        // ---- Moves learned in the selected style ----
        int ly = y0 + top;
        c.fill(mx0 + 8, ly - 4, x0 + W - 8, ly - 3, LINE);
        Ui.text(c, textRenderer, Ui.caps(selStyle.name()), mx0 + 10, ly + 2, 0.6f, accentOf(selStyle));
        if (!selStyle.blurb().isEmpty()) Ui.text(c, textRenderer, selStyle.blurb(), mx0 + 10 + Ui.width(textRenderer, Ui.caps(selStyle.name()), 0.6f) + 8, ly + 3, 0.45f, PAPER_FAINT);
        List<Move> moves = Deck.learned(selStyle);
        int gx = mx0 + 10, gy = ly + 14, gw = mw - 20, gh = y0 + H - 6 - gy;
        int cols = Math.max(1, (gw + 4) / (TILE_W + 4));
        rows = (moves.size() + cols - 1) / cols;
        int visRows = Math.max(1, (gh + 4) / (TILE_H + 4));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visRows)));
        for (int n = scroll * cols; n < moves.size(); n++) {
            int row = n / cols - scroll, col = n % cols;
            if (row >= visRows) break;
            Move m = moves.get(n);
            int tx = gx + col * (TILE_W + 4), ty = gy + row * (TILE_H + 4);
            boolean fits = m.fits(selStance, selSlot), over = in(mx, my, tx, ty, TILE_W, TILE_H), sel = m == selMove;
            float a = fits ? 1 : 0.35f;
            c.fill(tx, ty, tx + TILE_W, ty + TILE_H, sel ? 0x1CEDE5D3 : over ? 0x12EDE5D3 : 0x08EDE5D3);
            if (sel) Ui.edge(c, tx, ty, TILE_W, TILE_H, Ui.alpha(VERMILION, a));
            // Its style at the top.
            Style ms = Fighting.styleOf(m);
            String tag = Ui.caps(ms.name());
            c.fill(tx, ty, tx + TILE_W, ty + 7, Ui.alpha(Ui.darker(accentOf(ms), 0.45f), a));
            Ui.text(c, textRenderer, tag, tx + (TILE_W - Ui.width(textRenderer, tag, 0.35f)) / 2, ty + 2, 0.35f, Ui.alpha(PAPER, a));
            Icons.draw(c, m, tx + 6, ty + 10, 2, Ui.alpha(0xFF0E0C0A, a), Ui.alpha(m.heavy() ? GOLD : accentOf(ms), a));
            if (m.heavy()) c.fill(tx + 4, ty + TILE_H - 3, tx + TILE_W - 4, ty + TILE_H - 2, Ui.alpha(GOLD, a));
            boxes.add(new int[] {tx, ty, TILE_W, TILE_H, K_TILE, Fighting.id(m)});
        }
        if (rows > visRows) {
            int th = Math.max(8, gh * visRows / rows), tyb = gy + (gh - th) * scroll / Math.max(1, rows - visRows);
            c.fill(x0 + W - 5, gy, x0 + W - 4, gy + gh, LINE);
            c.fill(x0 + W - 5, tyb, x0 + W - 4, tyb + th, VERMILION);
        }

        // The name of whatever's under the cursor.
        if (!dragging) {
            for (int[] b : boxes) {
                if (!in(mx, my, b[0], b[1], b[2], b[3])) continue;
                Move m = b[4] == K_TILE ? Deck.move(b[5]) : b[4] == K_SLOT ? Deck.move(Deck.slot(selStance, b[5])) : null;
                if (m != null) c.drawTooltip(textRenderer, Text.literal(m.name() + (m.heavy() ? "  (hold)" : "")), mx, my);
                break;
            }
        }
        // What's being dragged, under the cursor.
        if (dragging && Deck.move(dragId) != null) {
            Move m = Deck.move(dragId);
            c.getMatrices().push();
            c.getMatrices().translate(0, 0, 300);
            Icons.draw(c, m, mx - 10, my - 10, 2, 0xFF0E0C0A, m.heavy() ? GOLD : accentOf(Fighting.styleOf(m)));
            c.getMatrices().pop();
        }
    }

    private static int indexOf(Style s) {
        for (int i = 0; i < Fighting.STYLES.length; i++) if (Fighting.STYLES[i] == s) return i;
        return 0;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int[] boxAt(double mx, double my) {
        for (int[] b : boxes) if (in(mx, my, b[0], b[1], b[2], b[3])) return b;
        return null;
    }

    private void preview(Move m) {
        if (m == null || m == selMove) return;
        selMove = m;
        Fight.previewStart = System.nanoTime();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int[] b = boxAt(mx, my);
        if (b == null) return super.mouseClicked(mx, my, button);
        pressX = mx;
        pressY = my;
        dragging = false;
        dragId = -1;
        dragFrom = -1;
        switch (b[4]) {
            case K_STYLE -> {
                selStyle = Fighting.STYLES[b[5]];
                Deck.show(selStyle);
                scroll = 0;
            }
            case K_QUAD -> {
                selStance = b[5];
                Move m = Deck.move(Deck.slot(selStance, selSlot));
                if (m != null) preview(m);
            }
            case K_SLOT -> {
                if (button == 1) {
                    Deck.set(selStance, b[5], -1);
                    break;
                }
                selSlot = b[5];
                int id = Deck.slot(selStance, b[5]);
                if (id >= 0) {
                    preview(Deck.move(id));
                    dragId = id;
                    dragFrom = b[5];
                }
            }
            case K_TILE -> {
                Move m = Deck.move(b[5]);
                preview(m);
                if (button == 1) {
                    // Straight into the selected slot.
                    if (Deck.set(selStance, selSlot, b[5]) && selSlot < Fighting.HEAVY_SLOT - 1) selSlot++;
                } else dragId = b[5];
            }
            case K_RESET -> Deck.reset();
            default -> { }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragId >= 0 && !dragging && Math.hypot(mx - pressX, my - pressY) > 3) dragging = true;
        return dragging || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            int[] b = boxAt(mx, my);
            if (b != null && b[4] == K_SLOT) {
                if (dragFrom >= 0) Deck.swap(selStance, dragFrom, b[5]);
                else Deck.set(selStance, b[5], dragId);
                selSlot = b[5];
            } else if (dragFrom >= 0) Deck.set(selStance, dragFrom, -1); // dragged off the chain: out it goes
        }
        dragging = false;
        dragId = -1;
        dragFrom = -1;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v));
        return true;
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
