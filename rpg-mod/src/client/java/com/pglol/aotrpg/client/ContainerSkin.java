package com.pglol.aotrpg.client;

import com.pglol.aotrpg.client.mixin.HandledScreenAccessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

/**
 * The vanilla storage screens redrawn: smoked glass with the gold rule, every slot a dark socket,
 * the container's own slots on a slightly lifted plate above yours, and the titles in the house
 * type instead of grey pixel text.
 */
public final class ContainerSkin {
    private ContainerSkin() {}

    public static boolean skinned(Object screen) {
        return screen instanceof GenericContainerScreen || screen instanceof ShulkerBoxScreen || screen instanceof HopperScreen
            || screen instanceof Generic3x3ContainerScreen;
    }

    public static void background(HandledScreen<?> s, DrawContext c) {
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        int x = a.aotrpg$x(), y = a.aotrpg$y(), w = a.aotrpg$w(), h = a.aotrpg$h();
        Ui.glass(c, x - 2, y - 2, w + 4, h + 4);
        // The container's slots and yours: where each block of slots sits.
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
        if (cTop != Integer.MAX_VALUE) {
            // A faint plate behind the container's own slots.
            c.fill(x + cL - 4, y + cTop - 4, x + cR + 4, y + cBot + 4, 0x30000000);
            c.drawBorder(x + cL - 4, y + cTop - 4, cR - cL + 8, cBot - cTop + 8, 0x18E0B96A);
        }
        if (pTop != Integer.MAX_VALUE) {
            // A hairline between theirs and yours.
            int ly = y + pTop - 13;
            c.fill(x + 8, ly, x + w - 8, ly + 1, 0x24FFFFFF);
        }
        for (Slot sl : s.getScreenHandler().slots) Ui.socket(c, x + sl.x - 1, y + sl.y - 1, 18);
    }

    /** Titles in the house type, light on the dark glass. True if drawn (vanilla's are skipped). */
    public static boolean foreground(HandledScreen<?> s, DrawContext c) {
        if (!skinned(s)) return false;
        HandledScreenAccessor a = (HandledScreenAccessor) s;
        Text title = s.getTitle();
        Ui.text(c, Ui.heading(title.getString()), a.aotrpg$titleX(), a.aotrpg$titleY(), 0.85f, Ui.CREAM, false);
        Ui.text(c, Text.literal(a.aotrpg$invTitle().getString()), a.aotrpg$invX(), a.aotrpg$invY() + 1, 0.7f, Ui.MUTED, false);
        return true;
    }
}
