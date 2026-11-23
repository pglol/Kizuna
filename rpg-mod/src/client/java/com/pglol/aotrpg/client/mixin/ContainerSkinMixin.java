package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ContainerSkin;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Chests, barrels, ender chests, shulker boxes, hoppers, dispensers and droppers in the house style. */
@Mixin({GenericContainerScreen.class, ShulkerBoxScreen.class, HopperScreen.class, Generic3x3ContainerScreen.class})
public abstract class ContainerSkinMixin {
    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$skin(DrawContext c, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        ContainerSkin.background((HandledScreen<?>) (Object) this, c);
        ci.cancel();
    }
}
