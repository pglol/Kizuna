package net.pglol.kata.client.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.pglol.kata.client.Rig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A posed player's body parts are drawn bent (see Rig); everything else as Minecraft draws it. */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;III)V", at = @At("HEAD"), cancellable = true)
    private void kata$bent(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color, CallbackInfo ci) {
        if (Rig.current != null && Rig.render((ModelPart) (Object) this, matrices, vertices, light, overlay, color)) ci.cancel();
    }
}
