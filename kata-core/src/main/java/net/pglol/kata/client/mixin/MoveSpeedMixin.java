package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.pglol.kata.client.Move;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Your ground speed follows the fast walk / run split (movement is worked out on your own client). */
@Mixin(LivingEntity.class)
public abstract class MoveSpeedMixin {
    @Inject(method = "getMovementSpeed()F", at = @At("RETURN"), cancellable = true)
    private void kata$speed(CallbackInfoReturnable<Float> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player) cir.setReturnValue(cir.getReturnValueF() * Move.speedFactor(mc.player));
    }
}
