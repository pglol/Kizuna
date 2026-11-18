package com.pglol.aotrpg.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * ODM gear is fast: a boost or a reel covers more than ten blocks a tick, which the vanilla
 * anti-cheat reads as "moved too quickly" and snaps you back (the rubber-banding). The limits
 * here are raised so fliers move cleanly; the small "moved wrongly" tolerance (where the server's
 * own collision disagrees by a hair at speed) is loosened too, so a fast pass by a wall doesn't
 * yank you back either.
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class FastMoveMixin {
    @ModifyConstant(method = "onPlayerMove", constant = @Constant(floatValue = 100.0F), require = 0)
    private float aotrpg$fastWalk(float f) {
        return 4000.0F;
    }

    @ModifyConstant(method = "onPlayerMove", constant = @Constant(floatValue = 300.0F), require = 0)
    private float aotrpg$fastFly(float f) {
        return 8000.0F;
    }

    @ModifyConstant(method = "onPlayerMove", constant = @Constant(doubleValue = 0.0625), require = 0)
    private double aotrpg$looseWalk(double d) {
        return 1.0;
    }

    @ModifyConstant(method = "onVehicleMove", constant = @Constant(doubleValue = 100.0), require = 0)
    private double aotrpg$fastRide(double d) {
        return 4000.0;
    }

    @ModifyConstant(method = "onVehicleMove", constant = @Constant(doubleValue = 0.0625), require = 0)
    private double aotrpg$looseRide(double d) {
        return 1.0;
    }
}
