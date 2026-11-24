package net.pglol.kizuna.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.pglol.kizuna.Brand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The loading screen: the pack's colour behind it, the pack's logo instead of Mojang's. */
@Mixin(SplashOverlay.class)
public class SplashOverlayMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private static void kizuna$logo(MinecraftClient client, CallbackInfo ci) {
        if (!Brand.active) return;
        NativeImage img = Brand.splashTexture();
        if (img != null) client.getTextureManager().registerTexture(Identifier.ofVanilla("textures/gui/title/mojangstudios.png"), new NativeImageBackedTexture(img));
    }

    @ModifyVariable(method = "withAlpha", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static int kizuna$background(int color) {
        return Brand.active ? Brand.background : color;
    }
}
