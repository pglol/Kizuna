package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Quiet feedback by the crosshair, the way you barely notice it: a gain (XP, Marks, a kill)
 * slides in small beside the reticle, adds up if more of the same comes, and fades. And with
 * the APG in hand, a dot under the crosshair for a round in the chamber, an arc filling as it
 * reloads.
 */
public final class HudPops {
    private HudPops() {}

    private static final class Pop {
        final String kind;
        long amount;
        String label;
        long at, bumped;

        Pop(String kind, long amount, String label, long at) {
            this.kind = kind;
            this.amount = amount;
            this.label = label;
            this.at = at;
            this.bumped = at;
        }
    }

    private static final List<Pop> pops = new ArrayList<>();
    private static final long LIFE = 2300;

    public static void on(Net.Pop p) {
        long now = Util.getMeasuringTimeMs();
        // More of the same while it's still up: it adds up instead of stacking lines.
        for (Pop o : pops) {
            if (o.kind.equals(p.kind()) && now - o.bumped < 1600) {
                o.amount += p.amount();
                if (!p.label().isEmpty()) o.label = p.label();
                o.bumped = now;
                return;
            }
        }
        pops.add(0, new Pop(p.kind(), p.amount(), p.label(), now));
        while (pops.size() > 3) pops.remove(pops.size() - 1);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        long now = Util.getMeasuringTimeMs();
        float cx = c.getScaledWindowWidth() / 2f, cy = c.getScaledWindowHeight() / 2f;
        gun(c, mc, cx, cy, tick.getTickDelta(true));
        pops.removeIf(p -> now - p.bumped > LIFE);
        float y = cy + 8;
        for (Pop p : pops) {
            float age = (now - p.at) / 1000f, since = (now - p.bumped) / (float) LIFE;
            float in = Math.min(1, age / 0.14f);
            float fade = since < 0.7f ? 1 : 1 - (since - 0.7f) / 0.3f;
            int a = (int) (235 * fade * in);
            if (a < 6) continue;
            float x = cx + 18 + (1 - in) * 8;
            // A tick of brightness when it adds up.
            float pulse = Math.max(0, 1 - (now - p.bumped) / 180f);
            int col = switch (p.kind) {
                case "marks" -> 0xE8C36F;
                case "kill" -> 0xFF6A5A;
                default -> 0xF4ECD8;
            };
            String amt = p.kind.equals("kill") ? p.label : "+" + String.format(java.util.Locale.ROOT, "%,d", p.amount) + (p.kind.equals("marks") ? " Marks" : " XP");
            float s = 0.68f + pulse * 0.08f;
            Ui.text(c, Text.literal(amt), x, y, s, (a << 24) | col, false);
            if (!p.kind.equals("kill") && !p.label.isEmpty()) {
                float w = Ui.font().getWidth(amt) * s;
                Ui.text(c, Text.literal(p.label), x + w + 4, y + 1, 0.55f, ((int) (a * 0.6f) << 24) | 0xB8B0A0, false);
            }
            y += 9;
        }
    }

    // ------------------------------------------------------------------ the APG

    private static boolean gunIn(ItemStack s) {
        var id = net.minecraft.registry.Registries.ITEM.getId(s.getItem());
        return id.getNamespace().equals("dannys-aot") && id.getPath().equals("apg_gun");
    }

    /** A round in the gun, from what it records about itself (or, if it says nothing, cartridges and no cooldown). */
    private static boolean loaded(MinecraftClient mc, ItemStack gun) {
        var data = gun.get(DataComponentTypes.CUSTOM_DATA);
        if (data != null) {
            var nbt = data.copyNbt();
            for (String k : nbt.getKeys()) {
                String l = k.toLowerCase(java.util.Locale.ROOT);
                if (l.contains("loaded") || l.contains("ammo") || l.contains("round") || l.contains("bullet")) {
                    var el = nbt.get(k);
                    if (el instanceof net.minecraft.nbt.AbstractNbtNumber n) return n.intValue() > 0;
                }
            }
        }
        if (mc.player.getItemCooldownManager().isCoolingDown(gun.getItem())) return false;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem());
            if (id.getNamespace().equals("dannys-aot") && id.getPath().equals("apg_cartridge")) return true;
        }
        return false;
    }

    private static float shown = -1;

    private static void gun(DrawContext c, MinecraftClient mc, float cx, float cy, float td) {
        ItemStack held = mc.player.getMainHandStack();
        if (!gunIn(held)) {
            shown = -1;
            return;
        }
        float reload = mc.player.getItemCooldownManager().getCooldownProgress(held.getItem(), td);
        boolean ready = loaded(mc, held);
        float want = ready ? 1 : 0;
        shown = shown < 0 ? want : shown + (want - shown) * 0.25f;
        float y = cy + 7.5f;
        if (reload > 0.01f) {
            // Reloading: a thin arc filling round the dot.
            double a0 = -Math.PI / 2, a1 = a0 + Math.PI * 2 * (1 - reload);
            Smooth.ring(c, cx, y, 3.2f, 0.8f, 0x40FFFFFF);
            Smooth.arc(c, cx, y, 2.8f, 3.6f, a0, a1, 0xD0E8C36F);
        } else if (shown > 0.5f) {
            Smooth.disc(c, cx, y, 1.6f, (int) (220 * MathHelper.clamp(shown, 0, 1)) << 24 | 0xF4ECD8);
        } else {
            // Empty: a hollow ring, a little red.
            Smooth.ring(c, cx, y, 2f, 0.8f, 0xB0E06A5A);
        }
    }
}
