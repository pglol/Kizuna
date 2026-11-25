package net.pglol.kata.client.mixin;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Arm;
import net.pglol.kata.client.Rig;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player model: its outer skin layer gets roles too, the pose's shoulder, hip and head turns are
 * added after Minecraft's own animation, and held items follow the chest and the forearm.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerModelMixin {
    @Shadow @Final public ModelPart leftSleeve;
    @Shadow @Final public ModelPart rightSleeve;
    @Shadow @Final public ModelPart leftPants;
    @Shadow @Final public ModelPart rightPants;
    @Shadow @Final public ModelPart jacket;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void kata$roles(ModelPart root, boolean thinArms, CallbackInfo ci) {
        Rig.tag(leftSleeve, Rig.Role.LEFT_SLEEVE);
        Rig.tag(rightSleeve, Rig.Role.RIGHT_SLEEVE);
        Rig.tag(leftPants, Rig.Role.LEFT_PANTS);
        Rig.tag(rightPants, Rig.Role.RIGHT_PANTS);
        Rig.tag(jacket, Rig.Role.JACKET);
    }

    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void kata$pose(LivingEntity entity, float limbAngle, float limbDistance, float age, float headYaw, float headPitch, CallbackInfo ci) {
        // The pose of the player being drawn (set by the renderer for this frame).
        Pose p = Rig.current;
        if (p == null || !(entity instanceof net.minecraft.entity.player.PlayerEntity)) return;
        BipedEntityModel<?> m = (BipedEntityModel<?>) (Object) this;
        if (p.gait) {
            // Our walk cycle replaces Minecraft's stick-leg swing (everything else it does stays).
            float c = net.minecraft.util.math.MathHelper.cos(limbAngle * 0.6662f);
            m.rightArm.pitch -= -c * limbDistance;
            m.leftArm.pitch -= c * limbDistance;
            m.rightLeg.pitch -= c * 1.4f * limbDistance;
            m.leftLeg.pitch -= -c * 1.4f * limbDistance;
            // Sneaking is our Crouch pose (Animator); take out Minecraft's stiff forward tilt so they don't stack.
            if (m.sneaking) {
                m.body.pitch = 0;
                m.rightArm.pitch -= 0.4f;
                m.leftArm.pitch -= 0.4f;
                m.rightLeg.pivotZ = 0;
                m.leftLeg.pivotZ = 0;
                m.rightLeg.pivotY = 12;
                m.leftLeg.pivotY = 12;
                m.head.pivotY = 0;
                m.body.pivotY = 0;
                m.rightArm.pivotY = 2;
                m.leftArm.pivotY = 2;
            }
        }
        add(m.head, p, Bone.HEAD);
        add(m.rightArm, p, Bone.RIGHT_ARM);
        add(m.leftArm, p, Bone.LEFT_ARM);
        add(m.rightLeg, p, Bone.RIGHT_LEG);
        add(m.leftLeg, p, Bone.LEFT_LEG);
        m.hat.copyTransform(m.head);
        rightSleeve.copyTransform(m.rightArm);
        leftSleeve.copyTransform(m.leftArm);
        rightPants.copyTransform(m.rightLeg);
        leftPants.copyTransform(m.leftLeg);
        jacket.copyTransform(m.body);
    }

    private static void add(ModelPart part, Pose p, Bone b) {
        part.pitch += p.rad(b, Pose.PITCH);
        part.yaw += p.rad(b, Pose.YAW);
        part.roll += p.rad(b, Pose.ROLL);
    }

    @Inject(method = "setArmAngle", at = @At("HEAD"))
    private void kata$itemChest(Arm arm, MatrixStack matrices, CallbackInfo ci) {
        Rig.root(matrices, Rig.current);
        Rig.chest(matrices, Rig.current);
    }

    @Inject(method = "setArmAngle", at = @At("TAIL"))
    private void kata$itemForearm(Arm arm, MatrixStack matrices, CallbackInfo ci) {
        BipedEntityModel<?> m = (BipedEntityModel<?>) (Object) this;
        Rig.forearm(matrices, arm == Arm.RIGHT ? m.rightArm : m.leftArm, arm == Arm.RIGHT, Rig.current);
    }
}
