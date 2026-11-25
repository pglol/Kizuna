package net.pglol.kata.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.client.Parkour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While you hang, climb or pull yourself up, parkour moves you instead of walking and gravity. */
@Mixin(PlayerEntity.class)
public abstract class TravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void kata$travel(Vec3d input, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player && Parkour.travel(mc.player)) ci.cancel();
    }
}
