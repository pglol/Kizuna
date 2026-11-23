package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.GearUi;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gear in any inventory gets a glow behind it in its rarity colour. */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    /** The house-style titles on the restyled storage screens (vanilla's grey ones skipped). */
    @Inject(method = "drawForeground", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$titles(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (com.pglol.aotrpg.client.ContainerSkin.foreground((HandledScreen<?>) (Object) this, context)) ci.cancel();
    }

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void aotrpg$rarity(DrawContext context, Slot slot, CallbackInfo ci) {
        if (slot.hasStack()) GearUi.backing(context, slot.getStack(), slot.x, slot.y);
    }
}
