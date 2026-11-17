package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/** Locked blade to blade: strike as fast as you can; the bar shows who's winning. */
public final class BladeLock {
    private BladeLock() {}

    private static Net.ClashLock lock;
    private static int presses, sent;
    private static boolean was;
    private static long shake;

    public static void on(Net.ClashLock l) {
        if (l.ticks() <= 0) {
            boolean won = l.balance() >= 0;
            MovesHud.popup(won ? "BLADE LOCK WON" : "OVERPOWERED", won ? 0xFFFFD24A : 0xFFE04A3A);
            lock = null;
            return;
        }
        if (lock == null) {
            presses = 0;
            sent = 0;
            MovesHud.popup("BLADE LOCK", 0xFFFFE8B0);
        }
        lock = l;
    }

    public static boolean active() {
        return lock != null;
    }

    public static void tick(MinecraftClient mc) {
        if (lock == null) return;
        boolean down = mc.options.attackKey.isPressed();
        // Every press counts (the swing itself is held off while locked).
        if (down && !was) {
            presses++;
            shake = Util.getMeasuringTimeMs();
        }
        was = down;
        if (presses != sent && mc.world.getTime() % 2 == 0) {
            sent = presses;
            ClientPlayNetworking.send(new Net.ClashMash(presses));
        }
    }

    public static void render(DrawContext c, RenderTickCounter t) {
        Net.ClashLock l = lock;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (l == null || mc.options.hudHidden) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        long now = Util.getMeasuringTimeMs();
        int jx = now - shake < 80 ? (int) ((Math.random() - 0.5) * 4) : 0;
        int bw = 160, bx = w / 2 - bw / 2 + jx, by = (int) (h * 0.62f);
        c.fill(bx - 2, by - 2, bx + bw + 2, by + 8, 0xC0000000);
        float k = MathHelper.clamp((l.balance() + 1) / 2, 0, 1);
        int split = bx + (int) (bw * k);
        c.fill(bx, by, split, by + 6, 0xFFF2C14E);
        c.fill(split, by, bx + bw, by + 6, 0xFFC0463A);
        c.fill(split - 1, by - 3, split + 1, by + 9, 0xFFFFFFFF);
        float pulse = 0.6f + 0.4f * MathHelper.sin(now / 60f);
        Ui.text(c, Ui.title("STRIKE!"), w / 2f + jx, by - 16, 1.3f, ((int) (255 * pulse) << 24) | 0xFFFFFF, true);
        Ui.text(c, Ui.heading("[" + mc.options.attackKey.getBoundKeyLocalizedText().getString() + "] as fast as you can"), w / 2f, by + 12, 0.6f, 0xD0FFFFFF, true);
    }
}
