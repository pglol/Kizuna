package net.pglol.kata.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No vanilla hotbar, hearts, armour, hunger, air or XP: Kata draws its own bars (see Hud). */
@Mixin(InGameHud.class)
public abstract class HudMixin {
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void kata$hotbar(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void kata$bars(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void kata$xp(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void kata$level(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "renderMountHealth", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$mount(CallbackInfo ci) {
        ci.cancel();
    }
}
