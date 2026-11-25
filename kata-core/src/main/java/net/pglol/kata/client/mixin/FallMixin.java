package net.pglol.kata.client.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.pglol.kata.KataCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A caught landing (crouch pressed just before touching down) takes much less fall damage. */
@Mixin(LivingEntity.class)
public abstract class FallMixin {
    @ModifyVariable(method = "handleFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private float kata$caught(float multiplier) {
        if ((Object) this instanceof ServerPlayerEntity p && KataCore.braced(p)) return multiplier * KataCore.CAUGHT_DAMAGE;
        return multiplier;
    }
}
