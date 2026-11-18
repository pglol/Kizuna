package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The storefront look: flat, sharp-cornered dark panels with hairline edges, a thin accent
 * strip on what matters, clean type, solid buttons. Used by the cosmetics library, the store
 * and the battle pass so they read as one product.
 */
public final class Sleek {
    private Sleek() {}

    public static final int BG = 0xF00B0E0D, BG2 = 0xF0121614, LINE = 0x24FFFFFF, LINE2 = 0x45FFFFFF, GOLD = 0xFFE8C36F, GOLD_DARK = 0xFF1A1408;
    public static final int[] TIER = {0xFF9A958A, 0xFF5A9AE0, 0xFFB06AE0, 0xFFF2C14E, 0xFFE8433A, 0xFFF4F0FF};
    public static final String[] TIER_NAME = {"Standard", "Rare", "Epic", "Legendary", "Mythic", "1 of 1"};

    /** Mouse position this frame (set by the screen), for hover states. */
    public static int mx, my;

    public static boolean over(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void panel(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, BG);
        c.drawBorder(x, y, w, h, LINE);
    }

    /** An item tile: a dark face, a hairline edge (accent on hover, gold when chosen), an accent strip on top. */
    public static void tile(DrawContext c, int x, int y, int w, int h, int accent, boolean hover, boolean chosen, boolean dim) {
        c.fillGradient(x, y, x + w, y + h, hover ? 0xF81C2220 : 0xF0161A18, hover ? 0xF8121614 : 0xF00D100F);
        int a = dim ? 0x40 : 0xFF;
        c.fill(x, y, x + w, y + 2, (a << 24) | (accent & 0xFFFFFF));
        // A faint wash of the accent under the strip.
        c.fillGradient(x, y + 2, x + w, y + 18, ((dim ? 0x08 : 0x1C) << 24) | (accent & 0xFFFFFF), 0x00000000);
        c.drawBorder(x, y, w, h, chosen ? GOLD : hover ? (0xB0 << 24) | (accent & 0xFFFFFF) : LINE);
    }

    /** A small tag (rarity, discount, state): tinted face, its own colour text. Returns its width. */
    public static int chip(DrawContext c, float x, float y, String text, int color) {
        float s = 0.55f;
        int w = (int) (Ui.font().getWidth(text) * s) + 8;
        c.fill((int) x, (int) y, (int) x + w, (int) y + 9, (0x38 << 24) | (color & 0xFFFFFF));
        c.fill((int) x, (int) y, (int) x + 1, (int) y + 9, color);
        Ui.text(c, Text.literal(text), x + 4.5f, y + 2, s, color, false);
        return w;
    }

    /** A price: "1,250 Gold", with the old price struck through beside it when discounted. */
    public static void price(DrawContext c, float x, float y, long gold, long was, float scale) {
        String p = String.format(java.util.Locale.ROOT, "%,d", gold);
        Ui.text(c, Text.literal(p), x + 9 * scale, y, scale, GOLD, false);
        coin(c, x + 3 * scale, y + 3.5f * scale, 3 * scale);
        if (was > gold) {
            String o = String.format(java.util.Locale.ROOT, "%,d", was);
            float ox = x + 12 * scale + Ui.font().getWidth(p) * scale;
            float ow = Ui.font().getWidth(o) * scale * 0.8f;
            Ui.text(c, Text.literal(o), ox, y + scale, scale * 0.8f, 0xFF7A766C, false);
            c.fill((int) ox, (int) (y + 4 * scale), (int) (ox + ow), (int) (y + 4 * scale) + 1, 0xFF9A958A);
        }
    }

    /** Wrapped small text; returns the y under it. */
    public static int wrap(DrawContext c, String text, int x, int y, int w, float scale, int color) {
        for (var line : Ui.font().wrapLines(Text.literal(text), (int) (w / scale))) {
            var ms = c.getMatrices();
            ms.push();
            ms.translate(x, y, 0);
            ms.scale(scale, scale, 1);
            c.drawText(Ui.font(), line, 0, 0, color, false);
            ms.pop();
            y += (int) Math.ceil(10 * scale);
        }
        return y;
    }

    /** A gold coin glyph. */
    public static void coin(DrawContext c, float x, float y, float r) {
        Smooth.disc(c, x, y, r, 0xFFB8892E);
        Smooth.disc(c, x, y, r * 0.72f, GOLD);
    }

    /** A solid button (primary: gold with dark text; else a hairline ghost). Registers its click. */
    public static void button(DrawContext c, Hits hits, int x, int y, int w, int h, String label, boolean primary, boolean enabled, Runnable run) {
        boolean hov = enabled && over(x, y, w, h);
        if (primary) {
            int face = !enabled ? 0xFF3A3528 : hov ? 0xFFF4D28A : GOLD;
            c.fill(x, y, x + w, y + h, face);
            Ui.text(c, Ui.heading(label), x + w / 2f, y + h / 2f - 3.5f, 0.75f, enabled ? GOLD_DARK : 0xFF7A766C, true);
        } else {
            c.fill(x, y, x + w, y + h, hov ? 0x30FFFFFF : 0x14FFFFFF);
            c.drawBorder(x, y, w, h, hov ? LINE2 : LINE);
            Ui.text(c, Ui.heading(label), x + w / 2f, y + h / 2f - 3.5f, 0.75f, enabled ? Ui.CREAM : Ui.DIM, true);
        }
        if (enabled && hits != null) hits.add(x, y, w, h, run);
    }

    /** A tab strip: returns nothing, registers each tab. */
    public static void tabs(DrawContext c, Hits hits, int x, int y, String[] names, int current, java.util.function.IntConsumer pick) {
        float cx = x;
        for (int i = 0; i < names.length; i++) {
            int w = (int) (Ui.font().getWidth(names[i]) * 0.8f) + 18;
            boolean sel = i == current, hov = over(cx, y, w, 16);
            Ui.text(c, Ui.heading(names[i]), cx + w / 2f, y + 4, 0.8f, sel ? GOLD : hov ? Ui.CREAM : 0xFF9A958A, true);
            if (sel) c.fill((int) cx + 4, y + 15, (int) cx + w - 4, y + 17, GOLD);
            int k = i;
            hits.add((int) cx, y, w, 17, () -> pick.accept(k));
            cx += w;
        }
    }

    /** Clickable regions for a frame, checked on click. */
    public static final class Hits {
        private record Hit(int x, int y, int w, int h, Runnable run) { }

        private final List<Hit> list = new ArrayList<>();

        public void clear() {
            list.clear();
        }

        public void add(int x, int y, int w, int h, Runnable run) {
            list.add(new Hit(x, y, w, h, run));
        }

        /** The topmost region under the point runs. */
        public boolean click(double mx, double my) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Hit h = list.get(i);
                if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                    h.run.run();
                    var mc = net.minecraft.client.MinecraftClient.getInstance();
                    mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.2f));
                    return true;
                }
            }
            return false;
        }
    }
}
