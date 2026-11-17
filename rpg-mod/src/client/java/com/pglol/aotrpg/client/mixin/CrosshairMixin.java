package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ShoulderCam;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Down the APG's sights the red ring is the only crosshair. */
@Mixin(InGameHud.class)
public class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void aotrpg$aimRing(DrawContext c, RenderTickCounter t, CallbackInfo ci) {
        if (ShoulderCam.aimingDown()) ci.cancel();
    }
}
