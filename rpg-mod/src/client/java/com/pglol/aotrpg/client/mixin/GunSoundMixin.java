package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The APG's own (placeholder) firing sound is left out: our gunshot plays instead. */
@Mixin(SoundSystem.class)
public abstract class GunSoundMixin {
    @Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$muteGun(SoundInstance sound, CallbackInfo ci) {
        var id = sound.getId();
        if (!id.getNamespace().equals("dannys-aot")) return;
        String p = id.getPath();
        if (p.contains("apg") || p.contains("gun") || p.contains("shoot") || p.contains("shot")) ci.cancel();
    }
}
