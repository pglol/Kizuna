package com.pglol.aotrpg.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players' vanilla block steps are replaced by real boot footsteps (client side, see Footsteps). */
@Mixin(Entity.class)
public abstract class PlayerStepMixin {
    @Inject(method = "playStepSound(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$bootSteps(BlockPos pos, BlockState state, CallbackInfo ci) {
        if ((Object) this instanceof PlayerEntity) ci.cancel();
    }
}
