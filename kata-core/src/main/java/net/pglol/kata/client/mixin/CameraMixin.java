package net.pglol.kata.client.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import net.pglol.kata.client.KataCam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Third person is the over-the-shoulder camera (see KataCam). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean thirdPerson;

    @Shadow protected abstract void setPos(double x, double y, double z);

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At("TAIL"))
    private void kata$shoulder(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        KataCam.Cam cam = KataCam.camera(tickDelta);
        if (cam == null) return;
        this.thirdPerson = true;
        setRotation(cam.yaw(), cam.pitch());
        setPos(cam.x(), cam.y(), cam.z());
    }
}
