package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.pglol.kata.client.Fight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Locked on, the camera holds the opponent and the mouse flicks your stance instead of turning you. */
@Mixin(Entity.class)
public abstract class LookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void kata$look(double dx, double dy, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player && Fight.locked()) {
            Fight.look(dx, dy);
            ci.cancel();
        }
    }
}
