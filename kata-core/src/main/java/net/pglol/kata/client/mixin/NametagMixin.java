package net.pglol.kata.client.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players get Kata's slim tags (Nametags), not the vanilla label. */
@Mixin(LivingEntityRenderer.class)
public abstract class NametagMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$noLabel(LivingEntity e, CallbackInfoReturnable<Boolean> cir) {
        if (e instanceof PlayerEntity) cir.setReturnValue(false);
    }
}
