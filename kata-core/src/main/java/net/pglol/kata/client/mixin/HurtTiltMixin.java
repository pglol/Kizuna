package net.pglol.kata.client.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Struck by a fighter: the view's jolt goes the way the blow came (ImpactFrames), not Minecraft's fixed hurt tilt. */
@Mixin(GameRenderer.class)
public abstract class HurtTiltMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void kata$jolt(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (net.pglol.kata.client.ImpactFrames.struckRecently()) ci.cancel();
    }
}
