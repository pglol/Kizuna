package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Motion;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Whole-body motion for players and people (see Motion), and no sideways tip in death. */
@Mixin(LivingEntityRenderer.class)
public class MotionMixin {
    @Inject(method = "setupTransforms(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V", at = @At("TAIL"))
    private void aotrpg$motion(LivingEntity e, MatrixStack ms, float anim, float bodyYaw, float tickDelta, float scale, CallbackInfo ci) {
        Motion.transforms(e, ms, bodyYaw, tickDelta);
    }

    @Inject(method = "getLyingAngle(Lnet/minecraft/entity/LivingEntity;)F", at = @At("HEAD"), cancellable = true)
    private void aotrpg$noSideways(LivingEntity e, CallbackInfoReturnable<Float> cir) {
        if (e.deathTime > 0 && (e instanceof PlayerEntity || e instanceof VillagerEntity)) cir.setReturnValue(0f);
    }
}
