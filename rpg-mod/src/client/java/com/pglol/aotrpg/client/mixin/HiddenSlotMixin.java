package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In chests and benches the blade slot (right hand) isn't shown at all: your blades live in their
 * sheaths and nothing else can go there. Your own inventory screen still shows it.
 */
@Mixin(HandledScreen.class)
public abstract class HiddenSlotMixin {
    private static boolean aotrpg$hidden(Object screen, Slot s) {
        if (screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen
            || screen instanceof com.pglol.aotrpg.client.RpgInventoryScreen) return false;
        return s.inventory instanceof PlayerInventory && s.getIndex() == 0 && MinecraftClient.getInstance().player != null;
    }

    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true)
    private void aotrpg$hideBladeSlot(DrawContext c, Slot slot, CallbackInfo ci) {
        if (!aotrpg$hidden(this, slot)) return;
        // Covers the frame the background drew for it.
        c.fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y + 17, 0xFFC6C6C6);
        ci.cancel();
    }

    @Inject(method = "getSlotAt", at = @At("RETURN"), cancellable = true)
    private void aotrpg$noBladeSlot(double x, double y, CallbackInfoReturnable<Slot> cir) {
        Slot s = cir.getReturnValue();
        if (s != null && aotrpg$hidden(this, s)) cir.setReturnValue(null);
    }
}
