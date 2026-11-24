package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Choosing how to play: two great cards side by side, each a living scene. The Open World is the
 * Walls at dusk with titans walking the plain beyond and birds turning over the towers;
 * Extraction is a blood-red sky with the balloon drifting over titan country and a flare rising.
 * They come up into view one after the other, lean toward the mouse, lift and glow when you're
 * over them. Click a card to switch to it. Extraction is a closed beta: shown, but locked unless
 * an admin has let you in.
 */
public final class GameModeScreen extends Screen {
    private final Sleek.Hits hits = new Sleek.Hits();
    private long opened;
    private final float[] lift = new float[4];

    public GameModeScreen() {
        super(Text.literal("Game mode"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        opened = Util.getMeasuringTimeMs();
        addDrawableChild(new AotButton(width - 30, 10, 20, 20, Text.literal("✕"), this::close));
    }

    private static String[] features(String id) {
        return id.equals("extraction")
            ? new String[] {"Squad up and drop into titan country", "Loot what you can, reach a flare to get out", "Fall, and everything you carried stays behind"}
            : new String[] {"Roam Paradis and Marley, towns to wilds", "Homes, trade, factions, contracts and raids", "Your gear is safe when you fall"};
    }

    private static int accent(String id) {
        return id.equals("extraction") ? 0xFFE04A3A : 0xFFE0B96A;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        hits.clear();
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        long now = Util.getMeasuringTimeMs();
        float t = (now - opened) / 1000f;
        float head = MathHelper.clamp(t / 0.5f, 0, 1);
        Ui.text(c, Ui.title("CHOOSE YOUR WAR"), width / 2f, 18 - (1 - head) * 10, 1.6f, (int) (255 * head) << 24 | 0xEDE3C8, true);
        Ui.text(c, Text.literal("Switch any time you're out of a fight"), width / 2f, 38, 0.65f, (int) (200 * head) << 24 | 0x8F8A7A, true);
        Net.ModeView v = ClientState.modes;
        if (v == null) {
            for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(c, mouseX, mouseY, delta);
            return;
        }
        int n = Math.max(1, v.modes().size());
        int gap = 18, cw = Math.min(300, (width - 40 - gap * (n - 1)) / n), ch = Math.min(300, height - 90);
        int total = n * cw + (n - 1) * gap, x0 = (width - total) / 2, y0 = 56;
        for (int i = 0; i < v.modes().size(); i++) {
            Net.ModeEntry m = v.modes().get(i);
            float in = (float) MathHelper.clamp((t - 0.15 - i * 0.12) / 0.55, 0, 1);
            in = 1 - (1 - in) * (1 - in) * (1 - in);
            if (in <= 0) continue;
            int x = x0 + i * (cw + gap), y = y0 + Math.round((1 - in) * 40);
            boolean over = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch;
            lift[i % 4] = MathHelper.lerp(Math.min(1, delta * 0.35f), lift[i % 4], over ? 1 : 0);
            card(c, m, x, y, cw, ch, in, lift[i % 4], mouseX, mouseY, t);
            if (m.unlocked() && !m.active()) hits.add(x, y, cw, ch, () -> ClientPlayNetworking.send(new Net.ModeAction(m.id())));
        }
        for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(c, mouseX, mouseY, delta);
    }

    private void card(DrawContext c, Net.ModeEntry m, int x, int y, int w, int h, float in, float lift, int mx, int my, float t) {
        int acc = accent(m.id());
        int a = (int) (255 * in);
        var ms = c.getMatrices();
        ms.push();
        float cx = x + w / 2f, cy = y + h / 2f;
        ms.translate(cx, cy - lift * 6, 0);
        float sc = 1 + lift * 0.025f;
        ms.scale(sc, sc, 1);
        ms.translate(-cx, -cy, 0);
        // A glow round it when it's under the mouse (or the mode you're in).
        float glow = Math.max(lift, m.active() ? 0.6f : 0);
        for (int i = 1; i <= 6; i++) {
            int ga = (int) (glow * 40 / i);
            if (ga > 0) c.drawBorder(x - i, y - i, w + i * 2, h + i * 2, ga << 24 | (acc & 0xFFFFFF));
        }
        // The scene (in its own clip), leaning with the mouse.
        float px = (mx - cx) / Math.max(1, width) * 14, py = (my - cy) / Math.max(1, height) * 8;
        int sh = (int) (h * 0.58f);
        c.enableScissor(x, y, x + w, y + sh);
        if (m.id().equals("extraction")) extraction(c, x, y, w, sh, t, px, py);
        else openWorld(c, x, y, w, sh, t, px, py);
        c.disableScissor();
        // Below the scene: the title, what it is, and the way in.
        c.fill(x, y + sh, x + w, y + h, 0xF00B0E10);
        c.fillGradient(x, y + sh - 30, x + w, y + sh, 0x000B0E10, 0xF00B0E10);
        c.fill(x, y + sh - 1, x + w, y + sh + 1, (a << 24) | (acc & 0xFFFFFF));
        Ui.text(c, Ui.title(m.title().toUpperCase(java.util.Locale.ROOT)), x + 14, y + sh - 24, 1.5f, (a << 24) | 0xF4EEE0, false);
        // Status chip.
        String chip = m.active() ? "PLAYING" : m.unlocked() ? "OPEN" : "CLOSED BETA";
        int chipCol = m.active() ? 0xFF8CC08A : m.unlocked() ? acc : 0xFF8F8A7A;
        Sleek.chip(c, x + w - 14 - (Ui.font().getWidth(chip) * 0.55f + 8), y + 10, chip, chipCol);
        int ty = y + sh + 10;
        Ui.wrapped(c, Text.literal(m.desc()), x + 14, ty, w - 28, (Math.min(a, 220) << 24) | 0xB8B2A4);
        ty += 34;
        for (String f : features(m.id())) {
            var mm = c.getMatrices();
            mm.push();
            mm.translate(x + 18, ty + 3, 0);
            mm.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(45));
            c.fill(-2, -2, 2, 2, (a << 24) | (acc & 0xFFFFFF));
            mm.pop();
            Ui.text(c, Text.literal(f), x + 26, ty, 0.62f, (a << 24) | 0xEDE3C8, false);
            ty += 11;
        }
        // The way in.
        int by = y + h - 30, bw = w - 28;
        String label = m.active() ? "YOU'RE HERE" : m.unlocked() ? "ENTER" : "BY INVITATION";
        int bg = m.active() ? 0x30FFFFFF : m.unlocked() ? (0xE0 << 24) | (acc & 0xFFFFFF) : 0x20FFFFFF;
        c.fill(x + 14, by, x + 14 + bw, by + 20, bg);
        if (m.unlocked() && !m.active() && lift > 0.05f) c.fill(x + 14, by, x + 14 + (int) (bw * lift), by + 2, 0xFFFFFFFF);
        Ui.text(c, Ui.heading(label), x + 14 + bw / 2f, by + 6, 0.8f, m.unlocked() && !m.active() ? 0xFF1A1408 : 0xFFB8B2A4, true);
        if (!m.unlocked()) Ui.text(c, Text.literal(m.requirement()), x + 14 + bw / 2f, by - 10, 0.55f, 0xFF8F8A7A, true);
        ms.pop();
    }

    // ------------------------------------------------------------------ the scenes

    /** The Walls at dusk: a warm sky, the great wall across it, titans walking the plain, birds. */
    private static void openWorld(DrawContext c, int x, int y, int w, int h, float t, float px, float py) {
        c.fillGradient(x, y, x + w, y + h, 0xFF2A3550, 0xFFE0985A);
        // The sun low on the wall.
        int sx = x + (int) (w * 0.68f + px * 0.3f), sy = y + (int) (h * 0.55f + py * 0.3f);
        for (int r = 26; r > 0; r -= 3) c.fill(sx - r, sy - r / 2, sx + r, sy + r / 2, (int) (40 + (26 - r) * 6) << 24 | 0xFFE8B0);
        // Far plain, titans walking it.
        int ground = y + (int) (h * 0.78f);
        c.fill(x, ground - 6, x + w, y + h, 0xFF3A3328);
        for (int i = 0; i < 4; i++) {
            float tx = ((t * (6 + i * 2) + i * 97) % (w + 60)) - 30;
            int hx = x + (int) (tx + px * 0.6f), ht = 22 + i * 7;
            c.fill(hx, ground - 6 - ht, hx + 6 + i, ground - 6, 0xFF241C16);
            c.fill(hx - 1, ground - 6 - ht - 6, hx + 7 + i, ground - 6 - ht, 0xFF241C16);
            int step = (int) (Math.sin(t * 3 + i) * 2);
            c.fill(hx + step, ground - 6, hx + 3 + step, ground - 2, 0xFF241C16);
        }
        // The Wall: a long dark band with its top lit.
        int wallTop = y + (int) (h * 0.5f + py);
        c.fill(x, wallTop, x + w, ground + 2, 0xFF1E1A16);
        c.fill(x, wallTop, x + w, wallTop + 2, 0xFF8A6A4A);
        for (int i = 0; i < w; i += 22) c.fill(x + i + (int) px, wallTop - 4, x + i + 10 + (int) px, wallTop, 0xFF1E1A16);
        // Town roofs in front.
        for (int i = 0; i < w; i += 18) {
            int rh = 10 + (int) (Math.abs(Math.sin(i * 1.7)) * 16);
            int rx = x + i + (int) (px * 1.6f);
            c.fill(rx, y + h - rh, rx + 16, y + h, 0xFF120F0C);
            c.fill(rx + 3, y + h - rh - 4, rx + 13, y + h - rh, 0xFF120F0C);
        }
        // Birds wheeling.
        for (int i = 0; i < 5; i++) {
            float a = t * 0.6f + i * 1.3f;
            int bx = x + (int) (w * 0.3f + Math.cos(a) * 40 + i * 9 + px), by = y + (int) (h * 0.25f + Math.sin(a * 1.3) * 10);
            int flap = (int) (Math.sin(t * 8 + i) * 2);
            c.fill(bx - 3, by - flap, bx, by + 1, 0xC0101010);
            c.fill(bx, by, bx + 3, by - flap + 1, 0xC0101010);
        }
    }

    /** Titan country under a red sky: the balloon adrift, a flare climbing, embers on the wind. */
    private static void extraction(DrawContext c, int x, int y, int w, int h, float t, float px, float py) {
        c.fillGradient(x, y, x + w, y + h, 0xFF1A0A0C, 0xFF8A2A1E);
        int ground = y + (int) (h * 0.8f);
        // Burning horizon.
        c.fillGradient(x, ground - 30, x + w, ground, 0x00E0603A, 0x80E0603A);
        // Giant trees and titans as silhouettes.
        for (int i = 0; i < 6; i++) {
            int tx = x + (int) (i * w / 5.5f + px * 0.8f), th = 40 + (i * 23) % 30;
            c.fill(tx, ground - th, tx + 7, ground, 0xFF0E0607);
            c.fill(tx - 12, ground - th - 10, tx + 19, ground - th + 6, 0xFF0E0607);
        }
        for (int i = 0; i < 2; i++) {
            float tx = ((t * (4 + i * 3) + i * 150) % (w + 80)) - 40;
            int hx = x + (int) (tx + px), ht = 46 + i * 12;
            c.fill(hx, ground - ht, hx + 10, ground, 0xFF080405);
            c.fill(hx - 2, ground - ht - 9, hx + 12, ground - ht, 0xFF080405);
            c.fill(hx + 3, ground - ht - 5, hx + 5, ground - ht - 3, 0xFFE04A3A);
        }
        c.fill(x, ground, x + w, y + h, 0xFF0A0405);
        // The balloon, drifting and bobbing.
        int bx = x + (int) (w * 0.3f + Math.sin(t * 0.4) * 12 + px * 0.4f), by = y + (int) (h * 0.22f + Math.sin(t * 1.1) * 3 + py * 0.4f);
        for (int r = 16; r > 0; r -= 2) c.fill(bx - r, by - r + 4, bx + r, by + r - 4, 0xFF2A1210 + (16 - r) * 0x040201);
        c.fill(bx - 1, by + 12, bx + 1, by + 22, 0xFF100808);
        c.fill(bx - 6, by + 22, bx + 6, by + 27, 0xFF1A0E0A);
        // The flare: rising, then hanging, then again.
        float ft = (t % 4f) / 4f;
        int fx = x + (int) (w * 0.72f + px), fy = ground - (int) (Math.min(1, ft * 1.6f) * h * 0.6f);
        for (int i = 0; i < 12; i++) c.fill(fx, fy + i * 4, fx + 1, fy + i * 4 + 3, (int) (200 - i * 16) << 24 | 0xFF6A3A);
        for (int r = 6; r > 0; r -= 2) c.fill(fx - r, fy - r, fx + r + 1, fy + r, (int) (60 + (6 - r) * 30) << 24 | 0xFFB07A);
        // Embers on the wind.
        for (int i = 0; i < 18; i++) {
            float e = (t * (0.3f + i % 5 * 0.07f) + i * 0.137f) % 1f;
            int ex = x + (int) ((i * 53 + e * 60) % w), ey = y + h - (int) (e * h);
            c.fill(ex, ey, ex + 1, ey + 1, (int) (255 * (1 - e)) << 24 | 0xFF8A4A);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }
}
