package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Reward reveals over the game (it keeps running underneath): a slash of light, the icon drops in
 * and bounces, rays turn behind it, the title types itself out, and it slides away. The rarity sets
 * how big it plays: longer, more rays, shockwaves from Epic, confetti and a fanfare from Legendary,
 * red flashes and a shake for Mythic. Several in a row queue up.
 */
public final class RewardFx {
    private RewardFx() {}

    private static final Deque<Net.RewardReveal> queue = new ArrayDeque<>();
    private static Net.RewardReveal now;
    private static long start;
    private static boolean sounded;

    public static void on(Net.RewardReveal r) {
        if (queue.size() < 8) queue.add(r);
    }

    private static int length(int rar) {
        return 3000 + rar * 300;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        long t = Util.getMeasuringTimeMs();
        if (now != null && t - start > length(now.rarity())) now = null;
        if (now == null) {
            // Wait out cutscenes and crate openings, then play the next one.
            if (queue.isEmpty() || com.pglol.aotrpg.client.story.CutscenePlayer.active() || CrateOpening.active()) return;
            now = queue.poll();
            start = t;
            sounded = false;
        }
        int rar = Math.max(0, Math.min(5, now.rarity()));
        boolean level = now.icon().equals("levelup");
        long e = t - start;
        int len = length(rar);
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        // Calm colours: the rarity's, softened toward ivory (a level is warm ivory and old gold).
        int col = level ? 0xE8D2A0 : Showcase.mix(CrateOpening.COLORS[rar] & 0xFFFFFF, 0xEDE3C8, 0.25f);
        if (!sounded) {
            sounded = true;
            sound(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, level ? 0.7f : 0.8f + rar * 0.05f);
            if (rar >= 4) sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.35f, 0.7f);
            if (rar == 5) sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.15f, 0.6f);
        }
        // In over half a second, held, then away up and out over the last half second.
        float in = ease(Math.min(1, e / 600f)), out = e > len - 600 ? ease((len - e) / 600f) : 1, a = in * out;
        float cx = w / 2f, cy = h * 0.24f - (1 - out) * 10;
        // A soft band of shade behind it, so it reads over anything.
        int band = (int) (0x60 * a);
        c.fillGradient(0, (int) cy - 34, w, (int) cy, 0, band << 24);
        c.fillGradient(0, (int) cy, w, (int) cy + 40, band << 24, 0);
        // The emblem: a glow, a diamond, a ring turning slowly round it for the rarer ones.
        float ey = cy - 16;
        Smooth.glow(c, cx, ey, 16 + 4 * a, (int) (0x50 * a) << 24 | col);
        Smooth.diamond(c, cx, ey, 3.5f + 1.5f * in, (int) (0xF0 * a) << 24 | col);
        if (rar >= 3 || level) {
            double r0 = e * 0.0012;
            Smooth.arc(c, cx, ey, 8.5f, 9.3f, r0, r0 + Math.PI * 1.3, (int) (0xA0 * a) << 24 | col);
            Smooth.arc(c, cx, ey, 8.5f, 9.3f, r0 + Math.PI, r0 + Math.PI * 1.6, (int) (0x60 * a) << 24 | col);
        }
        // The title, settling into place.
        float ty = cy - 4 + 5 * (1 - in);
        Ui.text(c, Ui.title(now.title()), cx, ty, 1.55f, (int) (255 * a) << 24 | col, true);
        // A hairline drawn out from the middle under it.
        float lw = 150 * ease(Math.min(1, Math.max(0, (e - 150) / 650f)));
        if (lw > 1) {
            Smooth.pill(c, cx - lw / 2, cy + 15, cx + lw / 2, cy + 16, (int) (0xB0 * a) << 24 | col);
            Smooth.dot(c, cx - lw / 2, cy + 15.5f, 1.2f, (int) (0xB0 * a) << 24 | col);
            Smooth.dot(c, cx + lw / 2, cy + 15.5f, 1.2f, (int) (0xB0 * a) << 24 | col);
        }
        // What it means, just after.
        float sa = ease(Math.min(1, Math.max(0, (e - 450) / 500f))) * out;
        if (sa > 0.03f && !now.sub().isEmpty()) {
            Ui.text(c, Ui.body(now.sub()), cx, cy + 21, 0.78f, (int) (230 * sa) << 24 | 0xE6DDC8, true);
        }
        if (!level && rar >= 2 && sa > 0.03f) {
            Ui.text(c, Ui.heading(CrateOpening.NAMES[rar].toUpperCase()), cx, cy + 33, 0.55f, (int) (170 * sa) << 24 | col, true);
        }
    }

    private static float ease(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static void sound(SoundEvent e, float vol, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.playSound(e, vol, pitch);
    }

    private static ItemStack icon(String id) {
        Identifier ident = Identifier.tryParse(id);
        var item = ident == null ? Items.NETHER_STAR : Registries.ITEM.get(ident);
        return new ItemStack(item == Items.AIR ? Items.NETHER_STAR : item);
    }

    public static void clear() {
        queue.clear();
        now = null;
    }
}
