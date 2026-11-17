package com.pglol.aotrpg.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.pglol.aotrpg.client.DyeTint;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Your dye colours the uniform you wear (whatever draws it), not only the outfit pieces. */
@Mixin(LivingEntityRenderer.class)
public class DyeArmorMixin {
    @WrapOperation(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/feature/FeatureRenderer;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;ILnet/minecraft/entity/Entity;FFFFFF)V"))
    private void aotrpg$dyeUniform(FeatureRenderer<?, ?> feature, MatrixStack ms, VertexConsumerProvider vc, int light, Entity e,
                                    float a, float b, float c, float d, float f, float g, Operation<Void> op) {
        op.call(feature, ms, DyeTint.wrap(feature, e, vc), light, e, a, b, c, d, f, g);
    }
}
