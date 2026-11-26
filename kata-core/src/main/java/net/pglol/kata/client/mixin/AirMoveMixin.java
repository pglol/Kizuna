package net.pglol.kata.client.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Kata moves you faster than vanilla expects (springs, wall jumps, pounces, dodges, juggles), which the
 * server's movement check reads as "moved too quickly" and snaps you back, worst just as you come down
 * (crouching into a landing pops you up). In the air, and for half a second after you land, the server
 * treats you as it does right after a teleport (when it doesn't check), so it moves cleanly. (As the AoT
 * mod does for ODM flight.) It lives in this package but runs on the server.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class AirMoveMixin {
    @Unique private int kata$airAge = -100;

    @Inject(method = "isInTeleportationState", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$airborne(CallbackInfoReturnable<Boolean> cir) {
        ServerPlayerEntity p = (ServerPlayerEntity) (Object) this;
        if (p.hasVehicle() || p.isFallFlying() || p.isSpectator() || p.getAbilities().flying) return;
        if (!p.isOnGround() && !p.isTouchingWater()) kata$airAge = p.age;
        if (p.age - kata$airAge <= 10) cir.setReturnValue(true);
    }
}
