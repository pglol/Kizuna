package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Small notifications that slide in at the right edge and slide away again, in place of the
 * big on-screen titles, chat notices and loot pickups; plus compact boss bars for waves and
 * shifters instead of the vanilla ones.
 */
public final class Toasts {
    private Toasts() {}

    private static final int W = 186, H = 28, MAX = 4;
    private static final long IN = 320, STAY = 3800, OUT = 420;

    private static final class Toast {
        Text title, sub;
        int accent;
        ItemStack icon;
        String key;
        long born;
        int count = 1;
    }

    private static final List<Toast> toasts = new ArrayList<>();
    private static Text pendingSub;
    private static long pendingAt;

    public static void push(Text title, Text sub, int accent, ItemStack icon, String key) {
        long now = Util.getMeasuringTimeMs();
        // The same thing again (more of an item, the same notice): update it instead of stacking.
        if (key != null) {
            for (Toast t : toasts) {
                if (key.equals(t.key) && now - t.born < STAY) {
                    t.title = title;
                    t.sub = sub;
                    t.born = Math.max(t.born, now - IN);
                    return;
                }
            }
        }
        Toast t = new Toast();
        t.title = title;
        t.sub = sub;
        t.accent = 0xFF000000 | (accent & 0xFFFFFF);
        // Only a picked-up item shows its picture; notices are text alone.
        t.icon = icon == null || key == null || !key.startsWith("loot:") ? ItemStack.EMPTY : icon;
        t.key = key;
        t.born = now;
        toasts.add(t);
        while (toasts.size() > MAX) toasts.remove(0);
    }

    private static int colorOf(Text t, int fallback) {
        TextColor c = t.getStyle().getColor();
        if (c == null && !t.getSiblings().isEmpty()) c = t.getSiblings().get(0).getStyle().getColor();
        return c == null ? fallback : c.getRgb();
    }

    /** A vanilla subtitle: held until its title arrives. */
    public static void subtitle(Text sub) {
        pendingSub = sub;
        pendingAt = Util.getMeasuringTimeMs();
    }

    /** A vanilla title: becomes a toast with the subtitle sent just before it. */
    public static void title(Text title) {
        Text sub = pendingSub != null && Util.getMeasuringTimeMs() - pendingAt < 1500 ? pendingSub : null;
        pendingSub = null;
        // Entering a place: its own quiet banner instead of a toast.
        if (AreaBanner.tryShow(title)) return;
        if (title.getString().isBlank() && sub == null) return;
        push(title, sub, colorOf(title, 0xE0B96A), null, "title:" + title.getString());
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || toasts.isEmpty()) return;
        long now = Util.getMeasuringTimeMs();
        int sw = c.getScaledWindowWidth();
        float y = Math.max(8, EffectCards.bottom + 4);
        for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
            Toast t = it.next();
            long age = now - t.born;
            if (age > IN + STAY + OUT) {
                it.remove();
                continue;
            }
            // A short drift in and fade, a rest, and a fade away.
            float k = age < IN ? ease(age / (float) IN) : age > IN + STAY ? 1 - ease((age - IN - STAY) / (float) OUT) : 1;
            int al = (int) (255 * k);
            if (al < 6) {
                y += (H + 4) * k;
                continue;
            }
            float x = sw - W - 10 + (1 - k) * 14;
            int iy = (int) y;
            // A dark glass card fading out to the left, a fine line of its colour down the edge.
            int accent = Showcase.mix(t.accent & 0xFFFFFF, 0xEDE3C8, 0.35f);
            c.fillGradient((int) x, iy, (int) x + W, iy + H, (int) (0xC8 * k) << 24 | 0x0A0D0C, (int) (0xC8 * k) << 24 | 0x0A0D0C);
            Smooth.pill(c, x + W - 1.5f, iy + 3, x + W - 0.5f, iy + H - 3, (int) (0xE0 * k) << 24 | accent);
            Smooth.pill(c, x, iy + H - 0.6f, x + W, iy + H, (int) (0x30 * k) << 24 | accent);
            float tx = x + 10;
            if (!t.icon.isEmpty()) {
                c.drawItem(t.icon, (int) x + 6, iy + 6);
                tx = x + 27;
            } else Smooth.diamond(c, x + 7, iy + (t.sub == null ? H / 2f : 9.5f), 1.6f, al << 24 | accent);
            if (t.icon.isEmpty()) tx = x + 13;
            int maxW = (int) (x + W - 8 - tx);
            Text title = fit(Ui.heading(t.title.getString()), maxW, 0.72f);
            Ui.text(c, title, tx, iy + (t.sub == null ? 10 : 5), 0.72f, al << 24 | accent, false);
            if (t.sub != null) Ui.text(c, fit(Ui.body(t.sub.getString()), maxW, 0.62f), tx, iy + 16, 0.62f, (int) (al * 0.85f) << 24 | 0xC8C0AE, false);
            y += (H + 4) * k;
        }
    }

    private static Text fit(Text t, int maxW, float scale) {
        var font = Ui.font();
        if (font.getWidth(t) * scale <= maxW) return t;
        String s = t.getString();
        while (s.length() > 1 && font.getWidth(s + "…") * scale > maxW) s = s.substring(0, s.length() - 1);
        return Text.literal(s + "…").setStyle(t.getStyle());
    }

    private static float ease(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    // ------------------------------------------------------------------ compact boss bars

    public static void bossBars(DrawContext c, Collection<ClientBossBar> bars) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || bars.isEmpty()) return;
        int sw = c.getScaledWindowWidth(), w = 150;
        int y = TopBar.bottom(), shown = 0;
        for (ClientBossBar b : bars) {
            if (shown++ >= 3) break;
            int x = (sw - w) / 2;
            int col = switch (b.getColor()) {
                case RED -> 0xFFC0463A;
                case BLUE -> 0xFF4A78C0;
                case GREEN -> 0xFF5BD35B;
                case YELLOW -> 0xFFE0B96A;
                case PURPLE -> 0xFF9A5AD0;
                case WHITE -> 0xFFE8E8E8;
                default -> 0xFFE06AA8;
            };
            c.fill(x - 3, y, x + w + 3, y + 15, 0xB80B0F0C);
            c.fill(x - 3, y, x - 1, y + 15, col);
            Ui.text(c, fit(b.getName(), w - 4, 0.62f), sw / 2f, y + 2, 0.62f, Ui.CREAM, true);
            c.fill(x, y + 10, x + w, y + 12, 0xFF1A1E1A);
            c.fill(x, y + 10, x + Math.round(w * Math.max(0, Math.min(1, b.getPercent()))), y + 12, col);
            y += 18;
        }
    }

    // ------------------------------------------------------------------ loot pickups

    /** An item picked up off the ground (repeat pickups of the same item add up). */
    public static void pickedUp(ItemStack stack, int amount) {
        if (amount <= 0) return;
        String key = "loot:" + stack.getItem();
        int total = amount;
        for (Toast t : toasts) if (key.equals(t.key) && Util.getMeasuringTimeMs() - t.born < STAY) total = t.count += amount;
        Text name = stack.getName();
        push(Text.literal("+" + total + " ").append(name), null, colorOf(name, 0xEDE3C8), stack.copyWithCount(1), key);
    }
}
