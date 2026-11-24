package net.pglol.kizuna.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MusicTracker;
import net.pglol.kizuna.Brand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Minecraft menu music on the pack's menus; music in the world is left alone. */
@Mixin(MusicTracker.class)
public abstract class MusicTrackerMixin {
    @Shadow public abstract void stop();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void kizuna$quietMenus(CallbackInfo ci) {
        if (Brand.active && MinecraftClient.getInstance().world == null) {
            stop();
            ci.cancel();
        }
    }
}
