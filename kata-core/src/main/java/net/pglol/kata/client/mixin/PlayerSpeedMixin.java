package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.pglol.kata.client.Move;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Players have their own ground speed (straight from the attribute), so the walk / fast walk / run /
 * guard speeds go on it here. If this game version has no player override, LivingEntity's (see
 * MoveSpeedMixin) is the one used and this does nothing: the factor is applied exactly once either way.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerSpeedMixin {
    @Inject(method = "getMovementSpeed()F", at = @At("RETURN"), cancellable = true, require = 0)
    private void kata$speed(CallbackInfoReturnable<Float> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player) cir.setReturnValue(cir.getReturnValueF() * Move.speedFactor(mc.player));
    }
}
