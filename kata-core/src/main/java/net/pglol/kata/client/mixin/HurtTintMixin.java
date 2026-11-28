package net.pglol.kata.client.mixin;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fighters don't flash red when they're struck: the hit shows in the body (the stagger, the hit-stop)
 * and at the point it met (sparks, the ring of air). Dying still reddens, as ever.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class HurtTintMixin {
    @Inject(method = "getOverlay", at = @At("RETURN"), cancellable = true)
    private static void kata$noRed(LivingEntity entity, float whiteOverlayProgress, CallbackInfoReturnable<Integer> cir) {
        if (entity.deathTime > 0) return;
        if (entity instanceof PlayerEntity || entity instanceof net.pglol.kata.Dummy)
            cir.setReturnValue(OverlayTexture.packUv(OverlayTexture.getU(whiteOverlayProgress), OverlayTexture.getV(false)));
    }
}
