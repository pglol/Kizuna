package net.pglol.kata.client.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hanging off a ledge or gripping a wall keeps you off the ground for a while; Minecraft would kick
 * that as flying unless the server allows flight. Kata servers always do (it doesn't let anyone fly:
 * that still needs creative or an op).
 */
@Mixin(MinecraftServer.class)
public abstract class FlightMixin {
    @Inject(method = "isFlightEnabled", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$flight(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
