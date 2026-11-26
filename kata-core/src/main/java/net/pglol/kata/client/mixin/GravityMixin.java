package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The top of your jump floats a moment (gravity eases off while you're barely rising or falling). */
@Mixin(Entity.class)
public abstract class GravityMixin {
    @Inject(method = "getFinalGravity", at = @At("RETURN"), cancellable = true, require = 0)
    private void kata$apex(CallbackInfoReturnable<Double> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity self = (Entity) (Object) this;
        if (mc.player == null || self != mc.player || self.isOnGround() || mc.player.getAbilities().flying) return;
        // Hanging from an air hit (given or taken): nearly weightless while the string goes on.
        if (net.pglol.kata.client.Fight.hanging()) {
            cir.setReturnValue(cir.getReturnValueD() * 0.18);
            return;
        }
        double vy = self.getVelocity().y;
        if (Math.abs(vy) < 0.12) cir.setReturnValue(cir.getReturnValueD() * (0.55 + 0.45 * Math.abs(vy) / 0.12));
    }
}
