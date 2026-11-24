package net.pglol.kizuna.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.pglol.kizuna.Brand;
import net.pglol.kizuna.BrandTitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The pack's own title screen in place of Minecraft's, and its name on the window. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen kizuna$title(Screen screen) {
        return Brand.active && screen instanceof TitleScreen ? new BrandTitleScreen() : screen;
    }

    @Inject(method = "getWindowTitle", at = @At("RETURN"), cancellable = true)
    private void kizuna$windowTitle(CallbackInfoReturnable<String> cir) {
        if (Brand.active && !Brand.name.isEmpty()) cir.setReturnValue(Brand.name);
    }
}
