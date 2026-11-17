package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.GasSwap;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands gas particles to GasSwap, so an ODM cosmetic can draw them in its own style instead. */
@Mixin(ParticleManager.class)
public class GasSwapMixin {
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void aotrpg$swapGas(ParticleEffect e, double x, double y, double z, double vx, double vy, double vz, CallbackInfoReturnable<Particle> cir) {
        if (GasSwap.swap(e, x, y, z, vx, vy, vz)) cir.setReturnValue(null);
    }

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void aotrpg$swapBuilt(Particle p, CallbackInfo ci) {
        if (GasSwap.swap(p)) ci.cancel();
    }
}
