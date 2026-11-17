package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Your moves on screen: a pop-up when one lands (the move's name punched in, then fading), and
 * just above the hotbar a row of pips for the big ones (slam, rising cut, burst) filling back up
 * as they come off cooldown.
 */
public final class MovesHud {
    private MovesHud() {}

    private static String text;
    private static int color;
    private static long at;

    private static int chain;
    private static long chainAt;

    /** A chain link landed: the counter at the side punches up. */
    public static void chain(int n) {
        chain = n;
        chainAt = Util.getMeasuringTimeMs();
    }

    public static void popup(String t, int argb) {
        text = t;
        color = argb;
        at = Util.getMeasuringTimeMs();
    }

    private record Pip(String label, long until, long length) { }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        if (text != null) {
            long t = now - at;
            if (t > 1100) text = null;
            else {
                // Punched in big, settling, then gone.
                float in = Math.min(1, t / 90f);
                float s = MathHelper.lerp(in, 2.6f, 1.6f);
                float a = t < 700 ? 1 : 1 - (t - 700) / 400f;
                int col = ((int) (255 * a) << 24) | (color & 0xFFFFFF);
                float y = h * 0.32f - (t > 700 ? (t - 700) / 40f : 0);
                Ui.text(c, Ui.title(text), w / 2f + 1.5f, y + 1.5f, s, ((int) (160 * a) << 24), true);
                Ui.text(c, Ui.title(text), w / 2f, y, s, col, true);
            }
        }
        if (chain >= 2 && now - chainAt < 1400) {
            long t = now - chainAt;
            float s = MathHelper.lerp(Math.min(1, t / 80f), 2.4f, 1.7f);
            float a = t < 1000 ? 1 : 1 - (t - 1000) / 400f;
            int col = chain >= 8 ? 0xFFFFD24A : chain >= 5 ? 0xFFFF9A4A : 0xFFFFFFFF;
            float x = w * 0.72f, y = h * 0.42f;
            Ui.text(c, Ui.title(chain + ""), x + 1.5f, y + 1.5f, s, ((int) (150 * a) << 24), true);
            Ui.text(c, Ui.title(chain + ""), x, y, s, ((int) (255 * a) << 24) | (col & 0xFFFFFF), true);
            Ui.text(c, Ui.heading("CHAIN"), x, y + 9 * s, 0.7f, ((int) (220 * a) << 24) | (col & 0xFFFFFF), true);
        }
        // Adrenaline: a thin bar over the hotbar while there's any; full, it burns.
        int am = AdrenalineClient.meter();
        if (am > 0) {
            int bw = 120, bx = w / 2 - bw / 2, by = h - 64;
            boolean full = am >= 100;
            float pulse = full ? 0.6f + 0.4f * MathHelper.sin(now / 90f) : 1;
            c.fill(bx - 1, by - 1, bx + bw + 1, by + 3, 0x90000000);
            c.fill(bx, by, bx + (int) (bw * am / 100f), by + 2, ((int) (255 * pulse) << 24) | (full ? 0xFF3A2A : 0xC0463A));
            int cost = (int) (bw * com.pglol.aotrpg.Adrenaline.CHARGE_COST / 100f);
            c.fill(bx + cost, by - 1, bx + cost + 1, by + 3, 0x80FFFFFF);
            if (full) Ui.text(c, Ui.heading("SURGE READY"), w / 2f, by - 9, 0.6f, ((int) (255 * pulse) << 24) | 0xFF5A3A, true);
        }
        Pip[] pips = {
            new Pip("SLAM", OdmMoves.slamReadyAt(), 10_000),
            new Pip("RISE", OdmMoves.riseReadyAt(), 8_000),
            new Pip("BURST", AdrenalineClient.burstReadyAt(), 3_500),
            new Pip("CRUSH", OdmMoves.crushReadyAt(), 9_000),
            new Pip("CHARGE", AdrenalineClient.chargeReadyAt(), 7_500)};
        boolean any = false;
        for (Pip p : pips) if (p.until() > now) any = true;
        if (!any) return;
        int pw = 34, gap = 4, total = pips.length * pw + (pips.length - 1) * gap;
        int x0 = w / 2 - total / 2, y0 = h - 58;
        for (int i = 0; i < pips.length; i++) {
            Pip p = pips[i];
            int x = x0 + i * (pw + gap);
            float left = Math.max(0, p.until() - now) / (float) p.length();
            boolean ready = left <= 0;
            c.fill(x, y0, x + pw, y0 + 10, 0x90000000);
            c.fill(x + 1, y0 + 8, x + 1 + (int) ((pw - 2) * (1 - Math.min(1, left))), y0 + 9, ready ? 0xFFF2C14E : 0xFF8A7A5A);
            Ui.text(c, Text.literal(p.label()), x + pw / 2f, y0 + 1.5f, 0.55f, ready ? 0xFFF2C14E : 0xFFB8B0A0, true);
        }
    }
}
