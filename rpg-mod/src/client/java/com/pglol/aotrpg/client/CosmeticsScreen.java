package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Cosmetics: looks only, never power. On the left every category with what you wear in it (the
 * overview); on the right a live preview on your character and the options of the chosen
 * category. Hover an option to preview it before equipping.
 */
public final class CosmeticsScreen extends Screen {
    private static final int TILE_H = 58, GAP = 8, RAIL_W = 150;
    private int left, top, w, h, stageX, stageW, gridX, gridW, scroll;
    private String slot = "outfit";
    private String hovered;
    /** The model's turn (degrees): drags with the mouse, drifts when left alone. */
    private float spin = 200, spinVel;
    private boolean dragging;
    private long lastFrame;

    public CosmeticsScreen() {
        super(Text.literal("Cosmetics"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    /** What you wear in a slot (the free default if nothing is chosen). */
    static String selected(String slot) {
        String s = ClientState.worn.get(slot);
        if (s != null && !s.isEmpty()) return s;
        return CosmeticFx.category(slot).entries().get(0).id();
    }

    private int gridTop() {
        return top + 26;
    }

    private int cols() {
        return Math.max(1, Math.min(3, (gridW + GAP) / (128 + GAP)));
    }

    private int tileW() {
        return (gridW - GAP * (cols() - 1)) / cols();
    }

    private int visibleRows() {
        return Math.max(1, (top + h - gridTop()) / (TILE_H + GAP));
    }

    private static boolean owned(CosmeticFx.Entry e, int index) {
        return ClientState.cosmeticsAll || ClientState.cosmetics.contains(e.id()) || index == 0;
    }

    private int rowH() {
        return Math.max(22, Math.min(30, (h - 8) / CosmeticFx.CATEGORIES.size()));
    }

    @Override
    protected void init() {
        w = Math.min(820, width - 24);
        h = Math.min(430, height - 64);
        left = (width - w) / 2;
        top = Math.max(46, (height - h) / 2 + 12);
        stageX = left + RAIL_W + 12;
        stageW = Math.max(170, Math.min(250, (w - RAIL_W) / 3));
        gridX = stageX + stageW + 12;
        gridW = left + w - gridX;
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        int rows = (cat.entries().size() + cols() - 1) / cols();
        scroll = Math.max(0, Math.min(scroll, rows - visibleRows()));
    }

    /** The tile under the mouse, as an index into the category, or -1. */
    private int cardAt(double mx, double my) {
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        int cols = cols(), cw = tileW();
        for (int i = scroll * cols; i < cat.entries().size() && i < (scroll + visibleRows()) * cols; i++) {
            int r = i / cols - scroll, col = i % cols;
            int x = gridX + col * (cw + GAP), y = gridTop() + r * (TILE_H + GAP);
            if (mx >= x && mx < x + cw && my >= y && my < y + TILE_H) return i;
        }
        return -1;
    }

    private int railAt(double mx, double my) {
        if (mx < left || mx > left + RAIL_W) return -1;
        int i = (int) ((my - top) / rowH());
        return my >= top && i >= 0 && i < CosmeticFx.CATEGORIES.size() ? i : -1;
    }

    private boolean onClose(double mx, double my) {
        double dx = mx - (left + w - 9), dy = my - (top - 27);
        return dx * dx + dy * dy < 11 * 11;
    }

    private void click() {
        if (client != null) client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(
            net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        if (onClose(mx, my)) {
            close();
            return true;
        }
        int r = railAt(mx, my);
        if (r >= 0) {
            slot = CosmeticFx.CATEGORIES.get(r).slot();
            scroll = 0;
            if (slot.equals("back")) spin = 20;
            else if (slot.equals("outfit") || slot.equals("dye") || slot.equals("head")) spin = 200;
            click();
            clearAndInit();
            return true;
        }
        int i = cardAt(mx, my);
        if (i >= 0) {
            CosmeticFx.Entry e = CosmeticFx.category(slot).entries().get(i);
            if (owned(e, i) && !e.id().equals(selected(slot))) {
                ClientPlayNetworking.send(new Net.SelectCosmetic(e.id()));
                click();
            }
            return true;
        }
        if (mx >= stageX && mx < stageX + stageW && my >= top && my < top + h) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            spin -= (float) dx * 1.6f;
            spinVel = (float) -dx * 1.6f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (mx > gridX) {
            scroll -= (int) Math.signum(vAmount);
            clearAndInit();
        }
        return true;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        int i = cardAt(mouseX, mouseY);
        hovered = i >= 0 ? CosmeticFx.category(slot).entries().get(i).id() : null;
        super.render(c, mouseX, mouseY, delta);
    }

    /** Your character, turned to `yaw`, wearing what's being previewed. */
    private void model(DrawContext c, float x, float feetY, float size, float yaw, String previewSlot, String previewId) {
        var p = client.player;
        if (p == null) return;
        float by = p.bodyYaw, yw = p.getYaw(), pt = p.getPitch(), ph = p.prevHeadYaw, hy = p.headYaw;
        p.bodyYaw = 180 + yaw;
        p.setYaw(180 + yaw);
        p.setPitch(0);
        p.headYaw = p.bodyYaw;
        p.prevHeadYaw = p.bodyYaw;
        CosmeticFx.previewSlot = previewSlot;
        CosmeticFx.previewId = previewId;
        try {
            org.joml.Quaternionf q = new org.joml.Quaternionf().rotateZ((float) Math.PI);
            org.joml.Quaternionf tilt = new org.joml.Quaternionf().rotateX((float) Math.toRadians(-6));
            q.mul(tilt);
            InventoryScreen.drawEntity(c, x, feetY - p.getHeight() / 2 * size, size, new org.joml.Vector3f(0, p.getHeight() / 2, 0), q, tilt, p);
        } finally {
            CosmeticFx.previewSlot = null;
            CosmeticFx.previewId = null;
            p.bodyYaw = by;
            p.setYaw(yw);
            p.setPitch(pt);
            p.prevHeadYaw = ph;
            p.headYaw = hy;
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xE00A0C0B, 0xF0050606);
        long now = Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (!dragging) {
            spinVel *= (float) Math.exp(-dt * 3);
            spin += spinVel + dt * 14;
        }
        CosmeticFx.Category cat = CosmeticFx.category(slot);
        String showId = hovered != null ? hovered : selected(slot);
        CosmeticFx.Entry show = CosmeticFx.entry(showId);
        float t = (now % 100000) / 1000f;

        // Header.
        Ui.text(c, Ui.title("COSMETICS"), left, top - 34, 1.25f, Ui.GOLD, false);
        int have = 0;
        for (int k = 0; k < cat.entries().size(); k++) if (owned(cat.entries().get(k), k)) have++;
        Ui.text(c, Text.literal((ClientState.cosmeticsAll ? "Everything unlocked" : have + " of " + cat.entries().size() + " owned")
            + "   \u00B7   looks only, never power"), left, top - 16, 0.62f, Ui.MUTED, false);
        boolean ch = onClose(mouseX, mouseY);
        Smooth.disc(c, left + w - 9, top - 27, 10, ch ? 0xFF3A2E1C : 0xC0141816);
        Smooth.ring(c, left + w - 9, top - 27, 10, 1, ch ? Ui.GOLD : 0x80B8955A);
        Ui.text(c, Text.literal("\u2715"), left + w - 9, top - 31, 0.8f, ch ? Ui.GOLD : Ui.CREAM, true);

        // The rail of categories.
        int rh = rowH(), hr = railAt(mouseX, mouseY);
        for (int k = 0; k < CosmeticFx.CATEGORIES.size(); k++) {
            CosmeticFx.Category cc = CosmeticFx.CATEGORIES.get(k);
            int y = top + k * rh;
            boolean sel = cc.slot().equals(slot), hov = k == hr;
            if (sel) {
                Smooth.pill(c, left, y + 1, left + RAIL_W, y + rh - 1, 0x40E0B96A);
                Smooth.pill(c, left, y + 4, left + 2.5f, y + rh - 4, Ui.GOLD);
            } else if (hov) Smooth.pill(c, left, y + 1, left + RAIL_W, y + rh - 1, 0x1EFFFFFF);
            CosmeticFx.Entry worn = CosmeticFx.entry(selected(cc.slot()));
            Ui.text(c, Ui.heading(cc.title()), left + 10, y + rh / 2f - 7, 0.72f, sel ? Ui.GOLD : hov ? Ui.CREAM : 0xFFC8BEA6, false);
            if (worn != null) Ui.text(c, Text.literal(fit(worn.title(), RAIL_W - 16, 0.58f)), left + 10, y + rh / 2f + 2, 0.58f,
                sel ? 0xFF000000 | worn.color() : Ui.MUTED, false);
        }

        // The stage: you, wearing it, turning slowly (drag to turn).
        Smooth.card(c, stageX, top, stageX + stageW, top + h, 0xC00D1110, 0x30B8955A);
        float scx = stageX + stageW / 2f, feet = top + h * 0.66f;
        float size = Math.min(stageW * 0.36f, h * 0.2f);
        Smooth.glow(c, scx, feet - size * 0.9f, stageW * 0.55f, 0x26000000 | (show == null ? 0xE0B96A : show.color()));
        Smooth.glow(c, scx, feet + 2, stageW * 0.3f, 0x90000000);
        c.enableScissor(stageX + 1, top + 1, stageX + stageW - 1, top + h - 1);
        boolean worn = slot.equals("outfit") || slot.equals("dye") || slot.equals("head") || slot.equals("back");
        model(c, scx, feet, size, spin, worn ? slot : null, worn ? showId : null);
        if (show != null && !worn) {
            // Effects: a sketch of it playing round you, scaled to the stage.
            var ms = c.getMatrices();
            ms.push();
            ms.translate(scx, feet, 200);
            float k = size / 38f;
            ms.scale(k, k, 1);
            ms.translate(-scx, -feet, 0);
            preview(c, slot, show, (int) scx, (int) feet, stageX, top, stageW, h);
            ms.pop();
        }
        c.disableScissor();
        if (show != null) {
            int sy = (int) (top + h * 0.72f);
            Ui.text(c, Ui.heading(show.title()), scx, sy, 1.05f, 0xFF000000 | show.color(), true);
            String tier = CosmeticFx.TIERS[show.tier()].toUpperCase();
            float tw = Ui.font().getWidth(tier) * 0.55f;
            int tc = CosmeticFx.TIER_COLORS[show.tier()];
            Smooth.pill(c, scx - tw / 2 - 6, sy + 13, scx + tw / 2 + 6, sy + 22, (0x40 << 24) | (tc & 0xFFFFFF));
            Ui.text(c, Text.literal(tier), scx, sy + 15, 0.55f, tc, true);
            var lines = Ui.font().wrapLines(Text.literal(show.desc()), (int) ((stageW - 24) / 0.66f));
            float yy = sy + 28;
            for (var ln : lines) {
                if (yy > top + h - 22) break;
                float lw = Ui.font().getWidth(ln) * 0.66f;
                var ms = c.getMatrices();
                ms.push();
                ms.translate(scx - lw / 2, yy, 0);
                ms.scale(0.66f, 0.66f, 1);
                c.drawText(Ui.font(), ln, 0, 0, Ui.CREAM, false);
                ms.pop();
                yy += 8;
            }
            String state = show.id().equals(selected(slot)) ? "Equipped" : hovered != null ? "Previewing  \u00B7  click to equip" : "";
            Ui.text(c, Text.literal(state), scx, top + h - 14, 0.6f, show.id().equals(selected(slot)) ? Ui.GOLD : Ui.MUTED, true);
        }
        Ui.text(c, Text.literal("drag to turn"), stageX + 8, top + 7, 0.5f, 0x80B8B0A0, false);

        // The library.
        Ui.text(c, Ui.heading(cat.title()), gridX, top + 2, 0.95f, Ui.CREAM, false);
        Ui.text(c, Text.literal(fit(cat.blurb(), gridW - 4, 0.58f)), gridX, top + 14, 0.58f, Ui.MUTED, false);
        int cols = cols(), cw = tileW();
        for (int k = scroll * cols; k < cat.entries().size() && k < (scroll + visibleRows()) * cols; k++) {
            CosmeticFx.Entry e = cat.entries().get(k);
            int r = k / cols - scroll, col = k % cols;
            int x = gridX + col * (cw + GAP), y = gridTop() + r * (TILE_H + GAP);
            tile(c, e, owned(e, k), e.id().equals(selected(slot)), e.id().equals(hovered), x, y, cw, t, k);
        }
        int rows = (cat.entries().size() + cols - 1) / cols;
        if (rows > visibleRows()) {
            int gy = gridTop(), gh = visibleRows() * (TILE_H + GAP) - GAP;
            Smooth.pill(c, left + w + 4, gy, left + w + 6, gy + gh, 0x30FFFFFF);
            int bh = Math.max(14, gh * visibleRows() / rows), by = gy + (gh - bh) * scroll / Math.max(1, rows - visibleRows());
            Smooth.pill(c, left + w + 4, by, left + w + 6, by + bh, Ui.GOLD);
        }
    }

    /** One item: a rounded tile, a glowing swatch of its colours, its name and rarity, and its state. */
    private void tile(DrawContext c, CosmeticFx.Entry e, boolean owned, boolean sel, boolean hov, int x, int y, int cw, float t, int i) {
        int tc = CosmeticFx.TIER_COLORS[e.tier()];
        int outline = sel ? Ui.GOLD : hov ? (0xC0 << 24) | (tc & 0xFFFFFF) : (0x50 << 24) | (tc & 0xFFFFFF);
        Smooth.card(c, x, y, x + cw, y + TILE_H, hov ? 0xF01A1F1C : 0xE0111513, owned ? outline : 0x30FFFFFF);
        // A soft wash of rarity along the bottom.
        c.fillGradient(x + 2, y + TILE_H - 14, x + cw - 2, y + TILE_H - 1, 0x00000000, (owned ? 0x30 : 0x10) << 24 | (tc & 0xFFFFFF));
        // The swatch: an orb in its two colours (turning, for the rarer ones).
        float ox = x + 22, oy = y + TILE_H / 2f;
        int c1 = e.color(), c2 = e.color2();
        if (e.id().contains("rainbow")) {
            c1 = MathHelper.hsvToRgb(t * 0.2f % 1f, 0.7f, 1f);
            c2 = MathHelper.hsvToRgb((t * 0.2f + 0.4f) % 1f, 0.7f, 1f);
        }
        int a = owned ? 0xFF : 0x60;
        if (e.tier() >= 2 && owned) Smooth.glow(c, ox, oy, 20, (0x50 << 24) | (c1 & 0xFFFFFF));
        Smooth.disc(c, ox, oy, 12, (a << 24) | (c2 & 0xFFFFFF));
        double sw = e.tier() >= 1 ? t * (0.6 + e.tier() * 0.3) + i : 0.8;
        Smooth.arc(c, ox, oy, 0, 12, sw, sw + Math.PI, (a << 24) | (c1 & 0xFFFFFF));
        Smooth.disc(c, ox - 4, oy - 4, 3, owned ? 0x50FFFFFF : 0x20FFFFFF);
        Smooth.ring(c, ox, oy, 12.5f, 1, 0x60000000);
        if (!owned) {
            // A small lock over the orb.
            Smooth.pill(c, ox - 5, oy - 1, ox + 5, oy + 7, 0xF0101010);
            Smooth.ring(c, ox, oy - 2, 3.5f, 1.4f, 0xF0101010);
        }
        float tx = x + 42;
        Ui.text(c, Ui.heading(fit(e.title(), cw - 50, 0.78f)), tx, y + 16, 0.78f, owned ? Ui.CREAM : Ui.DIM, false);
        Ui.text(c, Text.literal(CosmeticFx.TIERS[e.tier()]), tx, y + 28, 0.56f, owned ? tc : Ui.DIM, false);
        if (sel) {
            String eq = "\u2714 EQUIPPED";
            float ew = Ui.font().getWidth(eq) * 0.5f;
            Smooth.pill(c, tx - 2, y + 38, tx + ew + 4, y + 47, 0x40E0B96A);
            Ui.text(c, Text.literal(eq), tx + 1, y + 40, 0.5f, Ui.GOLD, false);
        } else if (!owned) Ui.text(c, Text.literal("LOCKED"), tx, y + 40, 0.5f, Ui.DIM, false);
        if (e.tier() == 4 && owned) {
            int pa = (int) (60 + 50 * Math.sin(t * 3 + i));
            Smooth.card(c, x - 1, y - 1, x + cw + 1, y + 1, (pa << 24) | 0xE02A2A, 0);
        }
    }

    private static int lerp(int a, int b, float u) {
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return (r << 16) | (g << 8) | bl;
    }

    private static String fit(String s, int w, float scale) {
        if (Ui.font().getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && Ui.font().getWidth(s + "...") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    /** An animated sketch of the effect around the model at (cx, feetY). */
    private void preview(DrawContext c, String slot, CosmeticFx.Entry e, int cx, int feetY, int px, int py, int pw, int ph) {
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        int col = e.color(), col2 = e.color2();
        boolean rainbow = e.id().contains("rainbow");
        switch (slot) {
            case "body" -> {
                if (e.id().equals("body_none")) return;
                for (int i = 0; i < 14; i++) {
                    float life = (t * 0.6f + i * 0.37f) % 1f;
                    double a = i * 2.4 + t * 0.5;
                    int x = cx + (int) (Math.cos(a) * 22), y = feetY - (int) (life * 90);
                    dot(c, x, y, i % 3 == 0 ? col2 : col, 1 - life, 2);
                }
            }
            case "head" -> {
                int hy = feetY - 92;
                switch (e.id()) {
                    case "head_halo" -> ellipse(c, cx, hy - 6 + (int) (Math.sin(t * 2) * 2), 16, 4, col, 1f);
                    case "head_crown" -> {
                        c.fill(cx - 12, hy - 2, cx + 12, hy + 3, 0xFF000000 | col);
                        for (int k = 0; k < 5; k++) c.fill(cx - 11 + k * 5, hy - 7, cx - 9 + k * 5, hy - 2, 0xFF000000 | col2);
                    }
                    case "head_planets", "head_orbs", "head_embers" -> {
                        boolean pl = e.id().equals("head_planets");
                        int n = e.id().equals("head_embers") ? 10 : 3;
                        for (int k = 0; k < n; k++) {
                            double a = t * (pl ? 1.2 : 2) + k * Math.PI * 2 / n;
                            int x = cx + (int) (Math.cos(a) * (pl ? 32 : 20)), y = hy + (pl ? 30 : 0) + (int) (Math.sin(a) * 5);
                            dot(c, x, y, k == 1 && pl ? col2 : col, 1f, pl ? 4 : 2);
                        }
                    }
                    case "head_laurel" -> ellipse(c, cx, hy + 2, 13, 3, col, 1f);
                    case "head_sun", "head_founder" -> {
                        for (int k = 0; k < 12; k++) {
                            double a = k * Math.PI / 6 + t * 0.4;
                            int len = k % 2 == 0 ? 20 : 14;
                            for (int d = 9; d < len; d += 2) dot(c, cx + (int) (Math.cos(a) * d), hy + 4 + (int) (Math.sin(a) * d), k % 2 == 0 ? col : col2, 1f, 2);
                        }
                    }
                    case "head_crows" -> {
                        for (int k = 0; k < 4; k++) {
                            double a = t * 1.1 + k * Math.PI / 2;
                            int x = cx + (int) (Math.cos(a) * 30), y = hy - 12 + (int) (Math.sin(a) * 4);
                            int f = (int) (Math.sin(t * 9 + k) * 2);
                            c.fill(x - 4, y + f, x, y + 1 + f, 0xFF1A1A22);
                            c.fill(x, y, x + 1, y + 2, 0xFF1A1A22);
                            c.fill(x + 1, y + f, x + 5, y + 1 + f, 0xFF1A1A22);
                        }
                    }
                    default -> { }
                }
            }
            case "odm", "horse", "trail" -> {
                // A streak flying across the preview.
                float p = (t * 0.7f) % 1f;
                int y = slot.equals("horse") ? feetY - 4 : feetY - 50;
                int headX = px + 10 + (int) (p * (pw - 20));
                for (int k = 0; k < 26; k++) {
                    int x = headX - k * 4;
                    if (x < px + 4) break;
                    int cc = rainbow ? MathHelper.hsvToRgb((k / 26f + t) % 1f, 0.8f, 1f) : k % 4 == 0 ? col2 : col;
                    dot(c, x, y + (int) (Math.sin(k * 0.9 + t * 6) * (slot.equals("trail") ? 0 : 2)), cc, 1 - k / 26f, 2);
                }
            }
            case "slash" -> {
                float p = (t * 1.3f) % 1f;
                for (int k = 0; k < 18; k++) {
                    float u = k / 17f;
                    if (u > p * 1.5f || u < p * 1.5f - 0.6f) continue;
                    double a = Math.toRadians(-60 + 120 * u);
                    int x = cx + (int) (Math.sin(a) * 34), y = feetY - 50 - (int) (Math.cos(a) * 22) + 14;
                    dot(c, x, y, k % 3 == 0 ? col2 : col, 1 - Math.abs(u - p), 3);
                }
            }
            case "block", "clash" -> {
                float p = (t * (slot.equals("clash") ? 1.1f : 0.9f)) % 1f;
                int bx = cx + 26, by = feetY - 60;
                if (slot.equals("clash")) {
                    c.fill(bx - 1, by - 14, bx + 1, by + 14, 0x80FFFFFF);
                }
                for (int k = 0; k < 16; k++) {
                    double a = k * 0.8 + (slot.equals("clash") ? 0 : -1.2);
                    int r = (int) (p * (slot.equals("clash") ? 34 : 22));
                    dot(c, bx + (int) (Math.cos(a) * r), by + (int) (Math.sin(a) * r * 0.8), k % 3 == 0 ? col2 : col, 1 - p, 2);
                }
            }
            case "back" -> {
                if (e.id().equals("back_none")) return;
                int sy = feetY - 70;
                if (e.id().startsWith("back_wings")) {
                    float flap = (float) Math.sin(t * 2.2) * 0.2f;
                    for (int s = -1; s <= 1; s += 2) {
                        for (int f = 0; f < 7; f++) {
                            double ang = Math.toRadians(-30 + f * 15) + flap;
                            int len = 34 + (3 - Math.abs(f - 3)) * 5;
                            for (int k = 3; k < len; k += 2) {
                                float u = k / (float) len;
                                dot(c, cx + (int) (s * Math.cos(ang) * k), sy - (int) (Math.sin(ang) * k), lerp(col, col2, u), 0.9f - u * 0.5f, 2);
                            }
                        }
                    }
                } else if (e.id().startsWith("back_cloak")) {
                    float sway = (float) Math.sin(t * 2.2) * 3;
                    for (int k = 0; k < 44; k++) {
                        int half = 12 + k / 8;
                        c.fill(cx - half + (int) (sway * k / 44f) + 30, sy + k, cx + half + (int) (sway * k / 44f) + 30, sy + k + 1, 0xFF000000 | col);
                    }
                    c.fill(cx + 26, sy + 12, cx + 34, sy + 20, 0xFF000000 | col2);
                } else if (e.id().equals("back_banner")) {
                    c.fill(cx + 22, sy - 40, cx + 24, sy + 30, 0xFF5A3A1A);
                    for (int k = 0; k < 30; k++) {
                        int wv = (int) (Math.sin(t * 5 - k * 0.3) * 2);
                        c.fill(cx + 24 + k, sy - 38 + wv, cx + 25 + k, sy - 20 + wv, 0xFF000000 | (k > 25 ? col2 : col));
                    }
                } else {
                    for (int i = 0; i < 12; i++) {
                        float life = (t * 0.7f + i * 0.21f) % 1f;
                        int s = i % 2 == 0 ? -1 : 1;
                        dot(c, cx + s * (10 + (int) (life * 16)), sy - (int) (life * 40), i % 3 == 0 ? col2 : col, 1 - life, 3);
                    }
                }
            }
            case "kill" -> {
                float p = (t * 0.5f) % 1f;
                int kx = px + pw - 60, ky = py + ph / 2 + 10;
                switch (e.id()) {
                    case "kill_holy" -> {
                        for (int k = 0; k < 30; k++) dot(c, kx + (int) (Math.cos(k + t * 3) * 6), ky - (int) (((k / 30f + p) % 1f) * 70), k % 3 == 0 ? col2 : col, 1f, 2);
                    }
                    case "kill_lightning" -> {
                        if (p < 0.35f) {
                            int bx = kx;
                            for (int yy = py + 4; yy < ky; yy += 4) {
                                int nx = bx + (int) ((Math.sin(yy * 1.7 + t) * 4));
                                c.fill(Math.min(bx, nx), yy, Math.max(bx, nx) + 2, yy + 4, 0xFFFFFFFF);
                                bx = nx;
                            }
                            if (p < 0.1f) c.fill(px + 1, py + 1, px + pw - 1, py + ph - 1, 0x40FFFFFF);
                        }
                    }
                    case "kill_wings" -> {
                        for (int s = -1; s <= 1; s += 2) for (int f = 0; f < 6; f++) {
                            double ang = Math.toRadians(-20 + f * 18);
                            int len = (int) (p * 40);
                            for (int k = 4; k < len; k += 3) dot(c, kx + (int) (s * Math.cos(ang) * k), ky - 20 - (int) (Math.sin(ang) * k), k > len - 6 ? col2 : col, 1 - p * 0.6f, 2);
                        }
                    }
                    case "kill_firework" -> {
                        for (int k = 0; k < 24; k++) {
                            double a = k * Math.PI * 2 / 24;
                            dot(c, kx + (int) (Math.cos(a) * p * 30), ky - 30 + (int) (Math.sin(a) * p * 30 + p * p * 10), k % 2 == 0 ? col : col2, 1 - p, 2);
                        }
                    }
                    case "kill_void" -> {
                        for (int k = 0; k < 20; k++) {
                            double a = k * 0.9 + t;
                            int r = (int) ((1 - p) * 34);
                            dot(c, kx + (int) (Math.cos(a) * r), ky - 20 + (int) (Math.sin(a) * r), k % 3 == 0 ? col2 : col, p, 2);
                        }
                    }
                    default -> {
                        for (int k = 0; k < 26; k++) {
                            float life = (p + k * 0.037f) % 1f;
                            dot(c, kx + (int) (Math.sin(k * 2.1) * 18), ky - (int) (life * 60), k % 3 == 0 ? col2 : col, 1 - life,
                                e.id().equals("kill_steam") ? 4 : 2);
                        }
                    }
                }
            }
            default -> { }
        }
    }

    private static void dot(DrawContext c, int x, int y, int rgb, float alpha, int size) {
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 255);
        if (a < 6) return;
        Smooth.disc(c, x + size / 2f, y + size / 2f, size * 0.62f, (a << 24) | (rgb & 0xFFFFFF));
    }

    private static void ellipse(DrawContext c, int cx, int cy, int rx, int ry, int rgb, float alpha) {
        for (int k = 0; k < 40; k++) {
            double a = k * Math.PI * 2 / 40;
            dot(c, cx + (int) (Math.cos(a) * rx), cy + (int) (Math.sin(a) * ry), rgb, alpha, 2);
        }
    }
}
