package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.pglol.kata.client.Fight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bare-handed, the mouse buttons are Kata's: left click punches (no block breaking, no vanilla
 * swing), right click guards (held; see Fight). Right click still works on blocks (doors, buttons).
 */
@Mixin(MinecraftClient.class)
public abstract class FightInputMixin {
    @Shadow public HitResult crosshairTarget;

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void kata$punch(CallbackInfoReturnable<Boolean> cir) {
        if (Fight.attack()) cir.setReturnValue(false);
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void kata$noBreaking(boolean breaking, CallbackInfo ci) {
        if (Fight.bareHands()) ci.cancel();
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void kata$guard(CallbackInfo ci) {
        if (Fight.bareHands() && (Fight.limpNow() || crosshairTarget == null || crosshairTarget.getType() != HitResult.Type.BLOCK)) ci.cancel();
    }
}
