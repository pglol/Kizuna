package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.SwingClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A blade swing takes as long as its direction says (see Swings): slower overheads, quicker thrusts. */
@Mixin(LivingEntity.class)
public abstract class SwingTimeMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
    private void aotrpg$swingTime(CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof PlayerEntity) || !com.pglol.aotrpg.Loadout.isMelee(self.getMainHandStack())) return;
        cir.setReturnValue(SwingClient.duration(self.getId(), cir.getReturnValue()));
    }
}
