package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.pglol.kata.client.Move;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No sprint dust kicked up by your fast walk; that's for running. */
@Mixin(Entity.class)
public abstract class SprintDustMixin {
    @Inject(method = "shouldSpawnSprintingParticles", at = @At("RETURN"), cancellable = true, require = 0)
    private void kata$dust(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (cir.getReturnValueZ() && mc.player != null && (Object) this == mc.player && Move.mode != Move.Mode.RUN) cir.setReturnValue(false);
    }
}
