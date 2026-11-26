package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * The HUD, kept to the edges and out of the way.
 *  - Bottom centre: health, a slim bar with a pale trail of what you just lost catching up a moment
 *    later; stamina, a hairline above it that dims when you've run yourself dry.
 *  - Bottom left: the fight tag. A small stance diamond and your style; squared up to someone, what a
 *    click and a hold will throw, with the heavy's charge.
 */
public final class Hud {
    private Hud() {}

    private static float trail = -1, shown = -1, engage, letterbox;
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
        int hw = 160, sw = 110;
        int hx = (w - hw) / 2, hy = h - 10;
        int sx = (w - sw) / 2, sy = hy - 5;

        // Health: a slim bar on a dark track, ticks every tenth, the loss trail behind it.
        track(c, hx, hy, hw, 3);
        c.fill(hx, hy, hx + Math.round(hw * trail), hy + 3, 0xB0F0E2D6);
        boolean low = hp < 0.25f;
        int col = low ? (((now / 250_000_000L) & 1) == 0 ? 0xFFE8383C : 0xFFB02A2D) : 0xFFDC3F3C;
        int fw = Math.round(hw * MathHelper.clamp(shown, 0, 1));
        if (fw > 0) {
            c.fillGradient(hx, hy, hx + fw, hy + 3, Ui.alpha(col, 1), Ui.darker(col, 0.75f));
            c.fill(hx, hy, hx + fw, hy + 1, 0x50FFFFFF);
            c.fill(hx + fw - 1, hy - 1, hx + fw, hy + 4, 0xC0FFFFFF); // the leading edge
        }
        for (int i = 1; i < 10; i++) c.fill(hx + hw * i / 10, hy, hx + hw * i / 10 + 1, hy + 3, 0x40000000);

        // Stamina: a hairline, warm when there's breath, dim when you're winded.
        track(c, sx, sy, sw, 1);
        int sf = Math.round(sw * MathHelper.clamp(Move.shownStamina, 0, 1));
        if (sf > 0) c.fill(sx, sy, sx + sf, sy + 1, Move.winded() ? 0xFF8C8268 : 0xFFF3E7BE);

        tag(c, mc, h, dt);
        c.getMatrices().pop();
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
        chip(c, tr, x, rowY, "TAP", light == null ? "—" : light.name(), Ui.alpha(Ui.TEXT, a), Ui.alpha(acc, a), a);
        chip(c, tr, x, rowY + 10, "HOLD", heavy == null ? "—" : heavy.name(), Ui.alpha(Ui.TEXT, a), Ui.alpha(Ui.HEAVY, a), a);
        if (Fight.chargingHeavy()) {
            float k = Fight.charge();
            int bw = 70;
            c.fill(x, rowY + 19, x + bw, rowY + 20, Ui.alpha(0xFF000000, 0.5f * a));
            c.fill(x, rowY + 19, x + Math.round(bw * k), rowY + 20, k >= 1 ? 0xFFFFE0A0 : Ui.HEAVY);
        }
    }

    private static void chip(DrawContext c, net.minecraft.client.font.TextRenderer tr, int x, int y, String key, String move, int textCol, int keyCol, float a) {
        float kw = Ui.width(tr, key, 0.5f);
        c.fill(x, y, x + Math.round(kw) + 5, y + 7, Ui.alpha(0xFF000000, 0.45f * a));
        c.fill(x, y + 7, x + Math.round(kw) + 5, y + 8, keyCol);
        Ui.text(c, tr, key, x + 2.5f, y + 1.5f, 0.5f, keyCol);
        Ui.text(c, tr, move, x + kw + 9, y + 0.5f, 0.7f, textCol);
    }
}
