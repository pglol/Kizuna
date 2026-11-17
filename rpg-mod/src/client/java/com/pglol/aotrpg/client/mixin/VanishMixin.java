package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.AdrenalineClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A body mid-vanish isn't drawn at all (nor its shadow, name or gear). */
@Mixin(EntityRenderDispatcher.class)
public class VanishMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void aotrpg$vanish(E entity, double x, double y, double z, float yaw, float tickDelta, MatrixStack matrices,
                                                   VertexConsumerProvider vc, int light, CallbackInfo ci) {
        if (AdrenalineClient.vanished(entity.getId())) ci.cancel();
    }
}
