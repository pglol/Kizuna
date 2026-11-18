package com.pglol.aotrpg.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ODM gear covers more than ten blocks a tick, which the vanilla movement check reads as "moved
 * too quickly" and snaps you back. Up in the air on the gear, the server treats you as it does
 * right after a teleport (when it doesn't check), so flight moves cleanly. (This leaves the
 * movement handler itself alone: other mods change it.)
 */
@Mixin(ServerPlayerEntity.class)
public abstract class AirborneMoveMixin {
    @Inject(method = "isInTeleportationState", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$flying(CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity p = (ServerPlayerEntity) (Object) this;
        if (!p.isOnGround() && !p.hasVehicle() && !p.isFallFlying() && !p.isSpectator() && !p.getAbilities().flying && !p.isTouchingWater()) {
            cir.setReturnValue(true);
        }
    }
}
