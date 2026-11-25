package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.pglol.kata.client.Move;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Your footstep sounds land with your feet. */
@Mixin(Entity.class)
public abstract class StepSoundMixin {
    @Shadow public float distanceTraveled;

    @Inject(method = "calculateNextStepSoundDistance", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$step(CallbackInfoReturnable<Float> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player) cir.setReturnValue(Move.nextStep(mc.player, distanceTraveled));
    }
}
