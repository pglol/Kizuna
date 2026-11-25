package net.pglol.kata.client.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.util.Identifier;
import net.pglol.kata.client.Rig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Every humanoid model's parts get their skeleton role (armour models included, so armour bends too). */
@Mixin(BipedEntityModel.class)
public abstract class BipedModelMixin {
    @Shadow @Final public ModelPart head;
    @Shadow @Final public ModelPart hat;
    @Shadow @Final public ModelPart body;
    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;
    @Shadow @Final public ModelPart rightLeg;
    @Shadow @Final public ModelPart leftLeg;

    @Inject(method = "<init>(Lnet/minecraft/client/model/ModelPart;Ljava/util/function/Function;)V", at = @At("RETURN"))
    private void kata$roles(ModelPart root, Function<Identifier, RenderLayer> layers, CallbackInfo ci) {
        Rig.tag(head, Rig.Role.HEAD);
        Rig.tag(hat, Rig.Role.HAT);
        Rig.tag(body, Rig.Role.BODY);
        Rig.tag(rightArm, Rig.Role.RIGHT_ARM);
        Rig.tag(leftArm, Rig.Role.LEFT_ARM);
        Rig.tag(rightLeg, Rig.Role.RIGHT_LEG);
        Rig.tag(leftLeg, Rig.Role.LEFT_LEG);
    }
}
