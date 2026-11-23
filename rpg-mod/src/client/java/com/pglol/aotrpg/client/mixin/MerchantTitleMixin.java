package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ContainerSkin;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A trader's titles, light on the dark glass (vanilla draws them dark grey). */
@Mixin(MerchantScreen.class)
public abstract class MerchantTitleMixin {
    @Inject(method = "drawForeground", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$titles(DrawContext c, int mouseX, int mouseY, CallbackInfo ci) {
        ContainerSkin.merchantTitles((HandledScreen<?>) (Object) this, c);
        ci.cancel();
    }
}
