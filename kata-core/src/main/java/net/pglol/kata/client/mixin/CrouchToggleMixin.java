package net.pglol.kata.client.mixin;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A tapped crouch stays down (Move.crouchOn) without the key held. */
@Mixin(KeyboardInput.class)
public abstract class CrouchToggleMixin {
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void kata$crouch(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        if (net.pglol.kata.client.Move.crouchOn) ((Input) (Object) this).sneaking = true;
    }
}
