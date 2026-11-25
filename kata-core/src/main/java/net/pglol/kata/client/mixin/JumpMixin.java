package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.pglol.kata.client.Parkour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A jump at a ledge is read from the ground in between: walk in and hop, or leap the gap (see Parkour). */
@Mixin(PlayerEntity.class)
public abstract class JumpMixin {
    @Inject(method = "jump", at = @At("HEAD"), cancellable = true, require = 0)
    private void kata$jump(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player && Parkour.preJump(mc.player)) ci.cancel();
    }
}
