package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * How wanted you are in Marley, quietly at the top of the screen: an eye that opens as the heat
 * rises, three marks for Suspected, Hunted, Manhunt, and a thin line of heat beneath. Gone when
 * nobody is looking for you.
 */
public final class WantedHud {
    private WantedHud() {}

    private static int heat, level;
    private static float shown;
    private static long changed;

    public static void on(Net.Wanted w) {
        if (w.level() != level) changed = Util.getMeasuringTimeMs();
        heat = w.heat();
        level = w.level();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        shown += (heat - shown) * 0.12f;
        if (shown < 1 && heat == 0) return;
        float cx = c.getScaledWindowWidth() / 2f, y = 14;
        long now = Util.getMeasuringTimeMs();
        float pulse = Math.max(0, 1 - (now - changed) / 900f);
        int red = level >= 3 ? 0xE02A2A : level >= 2 ? 0xE0603A : level >= 1 ? 0xE0B96A : 0xB8B0A0;
        int a = (int) (MathHelper.clamp(shown / 12f, 0, 1) * (level == 0 ? 150 : 230));
        // The eye: a lens shape opening with the heat.
        float open = MathHelper.clamp(shown / 100f, 0.15f, 1) * 3.2f;
        Smooth.poly(c, new float[] {cx - 8, y, cx, y - open, cx + 8, y, cx, y + open}, a << 24 | red);
        Smooth.disc(c, cx, y, 1.6f + open * 0.25f, a << 24 | 0x140C0A);
        if (pulse > 0) Smooth.ring(c, cx, y, 10 + pulse * 6, 1, (int) (pulse * 200) << 24 | red);
        // Three marks, either side of the eye.
        for (int i = 0; i < 3; i++) {
            boolean on = level > i;
            int col = (on ? a : a / 3) << 24 | (on ? red : 0x8A857A);
            Smooth.diamond(c, cx - 14 - i * 7, y, 2.2f, col);
            Smooth.diamond(c, cx + 14 + i * 7, y, 2.2f, col);
        }
        // The heat line.
        float w = 56 * MathHelper.clamp(shown / 100f, 0, 1);
        c.fill((int) (cx - 28), (int) y + 7, (int) (cx + 28), (int) y + 8, a / 3 << 24);
        c.fill((int) (cx - w / 2), (int) y + 7, (int) (cx + w / 2), (int) y + 8, a << 24 | red);
        if (level > 0) {
            String[] names = {"", "SUSPECTED", "HUNTED", "MANHUNT"};
            Ui.text(c, Text.literal(names[level]), cx, y + 11, 0.5f, a << 24 | red, true);
        }
    }
}
