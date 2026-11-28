package net.pglol.kata.client.mixin;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No vacuuming items up by walking over them: a fighter looks at one and presses F (see Kit.pickup). */
@Mixin(ItemEntity.class)
public abstract class ItemPickupMixin {
    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void kata$manual(PlayerEntity player, CallbackInfo ci) {
        if (!player.isCreative() && !player.isSpectator()) ci.cancel();
    }
}
