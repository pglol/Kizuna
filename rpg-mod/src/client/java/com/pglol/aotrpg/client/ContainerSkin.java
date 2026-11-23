package com.pglol.aotrpg.client;

import com.pglol.aotrpg.client.mixin.HandledScreenAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.client.gui.screen.ingame.BeaconScreen;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Every vanilla screen with slots, redrawn in the house style: smoked glass with the gold rule,
 * every slot a dark socket, the block's own slots on a lifted plate above yours, titles in the
 * house type. Each station keeps its working parts, drawn our way: a furnace's flame and arrow,
 * the brewing stand's fuel and progress, the enchanting table's three offers, the stonecutter's
 * choices, a trader's experience bar, a beacon's payments, a horse in its stall.
 */
public final class ContainerSkin {
    private ContainerSkin() {}

    private static final int ACCENT = 0xFFE0B96A, TRACK = 0x50FFFFFF;

    public static boolean skinned(Object s) {
        return s instanceof GenericContainerScreen || s instanceof ShulkerBoxScreen || s instanceof HopperScreen || s instanceof Generic3x3ContainerScreen
            || s instanceof AbstractFurnaceScreen<?> || s instanceof BrewingStandScreen || s instanceof CraftingScreen || s instanceof EnchantmentScreen
            || s instanceof ForgingScreen<?> || s instanceof GrindstoneScreen || s instanceof StonecutterScreen || s instanceof MerchantScreen
            || s instanceof BeaconScreen || s instanceof HorseScreen;
    }

    public static void background(HandledScreen<?> s, DrawContext c, int mouseX, int mouseY) {
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        int x = a.aotrpg$x(), y = a.aotrpg$y(), w = a.aotrpg$w(), h = a.aotrpg$h();
        Ui.glass(c, x - 2, y - 2, w + 4, h + 4);
        int cTop = Integer.MAX_VALUE, cBot = 0, cL = Integer.MAX_VALUE, cR = 0, pTop = Integer.MAX_VALUE;
        for (Slot sl : s.getScreenHandler().slots) {
            if (sl.inventory instanceof PlayerInventory) pTop = Math.min(pTop, sl.y);
            else {
                cTop = Math.min(cTop, sl.y);
                cBot = Math.max(cBot, sl.y + 16);
                cL = Math.min(cL, sl.x);
                cR = Math.max(cR, sl.x + 16);
            }
        }
        // Plain storage gets a plate behind its own slots; stations lay theirs out around their parts.
        boolean storage = s instanceof GenericContainerScreen || s instanceof ShulkerBoxScreen || s instanceof HopperScreen || s instanceof Generic3x3ContainerScreen;
        if (storage && cTop != Integer.MAX_VALUE) {
            c.fill(x + cL - 4, y + cTop - 4, x + cR + 4, y + cBot + 4, 0x30000000);
            c.drawBorder(x + cL - 4, y + cTop - 4, cR - cL + 8, cBot - cTop + 8, 0x18E0B96A);
        }
        if (pTop != Integer.MAX_VALUE) c.fill(x + 8, y + pTop - 13, x + w - 8, y + pTop - 12, 0x24FFFFFF);
        for (Slot sl : s.getScreenHandler().slots) {
            if (sl.isEnabled()) Ui.socket(c, x + sl.x - 1, y + sl.y - 1, 18);
        }
        try {
            parts(s, c, x, y, mouseX, mouseY);
        } catch (Throwable ignored) {
            // A station's parts never take the screen down with them.
        }
    }

    private static void parts(HandledScreen<?> s, DrawContext c, int x, int y, int mouseX, int mouseY) {
        var h = s.getScreenHandler();
        if (h instanceof AbstractFurnaceScreenHandler f) {
            // The flame (fuel left) between the two inputs, the arrow (cooking) to the output.
            int fuel = f.isBurning() ? f.getFuelProgress() : 0;
            flame(c, x + 57, y + 37, fuel / 13f);
            arrow(c, x + 80, y + 35, 24, f.getCookProgress() / 24f);
        } else if (h instanceof BrewingStandScreenHandler b) {
            // Blaze fuel under its slot, and the brew running down beside the ingredient.
            int fx = x + 60, fy = y + 44;
            c.fill(fx, fy, fx + 18, fy + 3, TRACK);
            c.fill(fx, fy, fx + Math.round(18 * MathHelper.clamp(b.getFuel() / 20f, 0, 1)), fy + 3, 0xFFE0903A);
            int bt = b.getBrewTime();
            float k = bt > 0 ? 1 - bt / 400f : 0;
            int px = x + 98, py = y + 17;
            c.fill(px, py, px + 3, py + 27, TRACK);
            c.fill(px, py, px + 3, py + Math.round(27 * k), 0xFFB07AE0);
        } else if (h instanceof EnchantmentScreenHandler e) {
            enchanting(s, e, c, x, y, mouseX, mouseY);
        } else if (h instanceof StonecutterScreenHandler sc && s instanceof StonecutterScreen ss) {
            stonecutter(ss, sc, c, x, y, mouseX, mouseY);
        } else if (h instanceof MerchantScreenHandler m) {
            // The list of offers sits in a well; the arrow points from what you pay to what you get.
            Ui.well(c, x + 4, y + 17, 89, 142);
            arrowTo(c, x, y, h.getSlot(2), 1);
            if (m.isLeveled()) {
                int lvl = m.getLevelProgress(), xp = m.getExperience();
                int bx = x + 136, by = y + 16;
                c.fill(bx, by, bx + 102, by + 3, TRACK);
                if (lvl < 5) {
                    int lo = net.minecraft.village.VillagerData.getLowerLevelExperience(lvl), hi = net.minecraft.village.VillagerData.getUpperLevelExperience(lvl);
                    float k = hi > lo ? MathHelper.clamp((xp - lo) / (float) (hi - lo), 0, 1) : 1;
                    c.fill(bx, by, bx + Math.round(102 * k), by + 3, 0xFF7AC85A);
                } else c.fill(bx, by, bx + 102, by + 3, ACCENT);
            }
        } else if (h instanceof ForgingScreenHandler fh) {
            arrowTo(c, x, y, h.getSlot(fh.getResultSlotIndex()), 1);
        } else if (s instanceof CraftingScreen) {
            arrowTo(c, x, y, h.getSlot(0), 1);
        } else if (s instanceof GrindstoneScreen) {
            arrowTo(c, x, y, h.getSlot(2), 1);
        } else if (s instanceof BeaconScreen) {
            // What it takes: one of these.
            c.drawItem(new ItemStack(Items.NETHERITE_INGOT), x + 20, y + 109);
            c.drawItem(new ItemStack(Items.EMERALD), x + 41, y + 109);
            c.drawItem(new ItemStack(Items.DIAMOND), x + 63, y + 109);
            c.drawItem(new ItemStack(Items.GOLD_INGOT), x + 86, y + 109);
            c.drawItem(new ItemStack(Items.IRON_INGOT), x + 108, y + 109);
            c.fill(x + 10, y + 7, x + 108, y + 8, 0x18FFFFFF);
        } else if (s instanceof HorseScreen hs) {
            Ui.well(c, x + 26, y + 18, 52, 52);
            var horse = ((com.pglol.aotrpg.client.mixin.HorseScreenAccessor) hs).aotrpg$entity();
            if (horse != null) net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(c, x + 26, y + 18, x + 78, y + 70, 17, 0.25f, mouseX, mouseY, horse);
        }
    }

    /** A flame filled from the bottom by how much fuel is left. */
    private static void flame(DrawContext c, int x, int y, float k) {
        c.fill(x + 4, y + 2, x + 10, y + 13, 0x30FFFFFF);
        int fill = Math.round(11 * MathHelper.clamp(k, 0, 1));
        if (fill > 0) {
            c.fillGradient(x + 4, y + 13 - fill, x + 10, y + 13, 0xFFFFD27A, 0xFFE0602A);
            c.fill(x + 6, y + 13 - fill - 1, x + 8, y + 13 - fill, 0xC0FFE8A8);
        }
    }

    /** An arrow len wide (track, the part done in gold, a head). */
    private static void arrow(DrawContext c, int x, int y, int len, float k) {
        int mid = y + 7;
        c.fill(x, mid - 1, x + len - 4, mid + 1, TRACK);
        int done = Math.round((len - 4) * MathHelper.clamp(k, 0, 1));
        if (done > 0) c.fill(x, mid - 1, x + done, mid + 1, ACCENT);
        int head = k >= 1 ? ACCENT : TRACK;
        for (int i = 0; i < 4; i++) c.fill(x + len - 4 + i, mid - 3 + i, x + len - 3 + i, mid + 3 - i, head);
    }

    /** An arrow running into a result slot from the left. */
    private static void arrowTo(DrawContext c, int x, int y, Slot out, float k) {
        arrow(c, x + out.x - 28, y + out.y + 1, 22, k);
    }

    private static void enchanting(HandledScreen<?> s, EnchantmentScreenHandler e, DrawContext c, int x, int y, int mouseX, int mouseY) {
        var mc = MinecraftClient.getInstance();
        int lapis = e.getLapisCount();
        for (int i = 0; i < 3; i++) {
            int rx = x + 60, ry = y + 14 + 19 * i, rw = 108, rh = 19;
            int need = e.enchantmentPower[i];
            boolean any = need > 0;
            boolean can = any && (mc.player.getAbilities().creativeMode || mc.player.experienceLevel >= need && lapis >= i + 1);
            boolean hov = mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + rh;
            c.fill(rx, ry, rx + rw, ry + rh - 1, !any ? 0x30000000 : can && hov ? 0x40E0B96A : 0x50000000);
            c.drawBorder(rx, ry, rw, rh - 1, can ? 0x50E0B96A : 0x18FFFFFF);
            if (!any) continue;
            // What it hints at, and what it costs.
            String hint = "";
            int id = e.enchantmentId[i];
            if (id >= 0 && mc.world != null) {
                var reg = mc.world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.ENCHANTMENT);
                var en = reg.getEntry(id);
                if (en.isPresent()) hint = net.minecraft.enchantment.Enchantment.getName(en.get(), e.enchantmentLevel[i]).getString() + "...";
            }
            int col = can ? Ui.CREAM : 0xFF6A665C;
            Ui.text(c, Text.literal(hint), rx + 5, ry + 6, 0.62f, col, false);
            String cost = need + "";
            Ui.text(c, Text.literal(cost), rx + rw - 5 - Ui.font().getWidth(cost) * 0.8f, ry + 5, 0.8f, can ? 0xFF8CE05A : 0xFF7A5A4A, false);
            for (int k = 0; k <= i; k++) c.fill(rx + rw - 26 - k * 4, ry + 13, rx + rw - 24 - k * 4, ry + 15, can ? 0xFF4A6AD8 : 0xFF3A3A48);
        }
    }

    private static void stonecutter(StonecutterScreen ss, StonecutterScreenHandler h, DrawContext c, int x, int y, int mouseX, int mouseY) {
        var acc = (com.pglol.aotrpg.client.mixin.StonecutterScreenAccessor) ss;
        int off = acc.aotrpg$scrollOffset();
        Ui.well(c, x + 51, y + 13, 66, 56);
        var mc = MinecraftClient.getInstance();
        var list = h.getAvailableRecipes();
        for (int i = off; i < Math.min(list.size(), off + 12); i++) {
            int k = i - off, rx = x + 52 + (k % 4) * 16, ry = y + 14 + (k / 4) * 18;
            boolean sel = i == h.getSelectedRecipe();
            boolean hov = mouseX >= rx && mouseX < rx + 16 && mouseY >= ry && mouseY < ry + 18;
            c.fill(rx, ry, rx + 16, ry + 18, sel ? 0x60E0B96A : hov ? 0x30FFFFFF : 0x18FFFFFF);
            if (sel) c.drawBorder(rx, ry, 16, 18, ACCENT);
            c.drawItem(list.get(i).value().getResult(mc.world.getRegistryManager()), rx, ry + 1);
        }
        // The scrollbar.
        float amt = acc.aotrpg$scrollAmount();
        c.fill(x + 120, y + 15, x + 123, y + 69, TRACK);
        int ty = y + 15 + Math.round(41 * amt);
        c.fill(x + 119, ty, x + 124, ty + 13, list.size() > 12 ? ACCENT : 0x40E0B96A);
    }

    /** Titles in the house type, light on the dark glass. True if drawn (vanilla's are skipped). */
    public static boolean foreground(HandledScreen<?> s, DrawContext c) {
        if (!skinned(s) || s instanceof MerchantScreen || s instanceof BeaconScreen) return false;
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        Ui.text(c, Ui.heading(s.getTitle().getString()), a.aotrpg$titleX(), a.aotrpg$titleY(), 0.85f, Ui.CREAM, false);
        Ui.text(c, Text.literal(a.aotrpg$invTitle().getString()), a.aotrpg$invX(), a.aotrpg$invY() + 1, 0.7f, Ui.MUTED, false);
        return true;
    }

    public static void merchantTitles(HandledScreen<?> s, DrawContext c) {
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        String t = s.getTitle().getString();
        Ui.text(c, Ui.heading(t), 49 + a.aotrpg$w() / 2f, 6, 0.85f, Ui.CREAM, true);
        Ui.text(c, Text.literal("Trades"), 48, 6, 0.7f, Ui.GOLD, true);
        Ui.text(c, Text.literal(a.aotrpg$invTitle().getString()), a.aotrpg$invX(), a.aotrpg$invY() + 1, 0.7f, Ui.MUTED, false);
    }
}
