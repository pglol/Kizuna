package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.pglol.kata.client.Move;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The sprint zoom belongs to the run, not the fast walk. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class RunFeelMixin {
    @Inject(method = "getFovMultiplier", at = @At("RETURN"), cancellable = true, require = 0)
    private void kata$fov(CallbackInfoReturnable<Float> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player) cir.setReturnValue(Move.fov(mc.player, cir.getReturnValueF()));
    }
}
