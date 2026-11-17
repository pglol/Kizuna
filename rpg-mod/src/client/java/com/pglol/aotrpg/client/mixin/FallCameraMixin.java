package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.FallFx;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** In a long fall the ordinary third-person camera eases back too. */
@Mixin(Camera.class)
public class FallCameraMixin {
    @ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;clipToSpace(F)F"))
    private float aotrpg$fallBack(float d) {
        return d + 3f * FallFx.amount();
    }
}
