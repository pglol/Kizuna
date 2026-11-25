package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Two clean bars at the bottom of the screen: health, and stamina just above it, a bit narrower.
 * Health keeps a pale trail of what you just lost that catches up a moment later; stamina dims when
 * you've run yourself dry.
 */
public final class Hud {
    private Hud() {}

    private static float trail = -1, shown = -1;
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
        int hw = 182, sw = 124;
        int hx = (w - hw) / 2, hy = h - 12;
        int sx = (w - sw) / 2, sy = hy - 6;

        // Health
        bar(c, hx, hy, hw, 4, trail, 0xFFE8D6C8);
        int col = hp < 0.25f ? (((now / 250_000_000L) & 1) == 0 ? 0xFFE0383B : 0xFFB02A2D) : 0xFFD8403E;
        fill(c, hx, hy, hw, 4, shown, col);

        // Stamina
        float st = Move.shownStamina;
        bar(c, sx, sy, sw, 2, 0, 0);
        fill(c, sx, sy, sw, 2, st, Move.stamina < 0.25f ? 0xFF9C8F6A : 0xFFF2E6B8);
    }

    /** The track (dark, with a thin outline), and optionally a trail fill. */
    private static void bar(DrawContext c, int x, int y, int w, int h, float trailFrac, int trailCol) {
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x90000000);
        c.fill(x, y, x + w, y + h, 0x60202020);
        if (trailFrac > 0) c.fill(x, y, x + Math.round(w * trailFrac), y + h, trailCol & 0x00FFFFFF | 0xB0000000);
    }

    private static void fill(DrawContext c, int x, int y, int w, int h, float frac, int col) {
        int fw = Math.round(w * MathHelper.clamp(frac, 0, 1));
        if (fw <= 0) return;
        c.fill(x, y, x + fw, y + h, col);
        // A lighter top edge so it reads as a bar, not a line.
        if (h > 2) c.fill(x, y, x + fw, y + 1, 0x40FFFFFF);
    }
}
