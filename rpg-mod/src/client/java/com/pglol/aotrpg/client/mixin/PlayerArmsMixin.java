package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.InfusionFx;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers each player's arm pose as last drawn, so blade effects can follow the real arms. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerArmsMixin {
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"), require = 0)
    private void aotrpg$arms(LivingEntity e, float a, float b, float c, float d, float f, CallbackInfo ci) {
        PlayerEntityModel<?> m = (PlayerEntityModel<?>) (Object) this;
        // Run styles and air moves first, so the blades' effects follow the arms as drawn.
        com.pglol.aotrpg.client.Poses.apply(m, e, a, b, c);
        // Hits, throws, jumps, falls, landings and death on top.
        com.pglol.aotrpg.client.Motion.pose(m, e, c);
        InfusionFx.arms(e.getId(), m.rightArm, m.leftArm);
    }
}
