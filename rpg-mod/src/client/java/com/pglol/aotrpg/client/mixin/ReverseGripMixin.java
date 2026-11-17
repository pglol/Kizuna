package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.Poses;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Reverse Grip run: blades turned round in the fist, lying back along the forearms. */
@Mixin(HeldItemFeatureRenderer.class)
public class ReverseGripMixin {
    @Inject(method = "renderItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V"))
    private void aotrpg$reverse(LivingEntity e, ItemStack stack, ModelTransformationMode mode, Arm arm, MatrixStack ms, VertexConsumerProvider vc, int light, CallbackInfo ci) {
        float k = Poses.reverseGrip(e);
        if (k <= 0.01f) return;
        // Turned about the fist (a little up the handle), so the grip stays in the hand and the
        // blade swings round behind it.
        var c = com.pglol.aotrpg.client.ShoulderCam.cfg;
        float py = c.gripAlong, pz = c.gripDepth;
        ms.translate(c.gripSide * k, 0, 0);
        ms.translate(0, py, pz);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180 * k));
        ms.translate(0, -py, -pz);
    }
}
