package net.pglol.kata.client.mixin;

import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The mouse wheel doesn't pick hotbar slots (the hotbar is gone; those inputs are for moves later). */
@Mixin(PlayerInventory.class)
public abstract class HotbarScrollMixin {
    @Inject(method = "scrollInHotbar", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$scroll(double amount, CallbackInfo ci) {
        ci.cancel();
    }
}
