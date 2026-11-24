package net.pglol.kizuna.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.pglol.kizuna.Brand;
import net.pglol.kizuna.BrandTitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The pack's own title screen in place of Minecraft's, and its name on the window. The server list,
 * singleplayer and Realms never open (leaving a server normally drops you on the server list): you
 * land back on the pack's title screen instead.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen kizuna$title(Screen screen) {
        if (!Brand.active) return screen;
        if (screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof SelectWorldScreen
            || screen instanceof CreateWorldScreen || screen instanceof RealmsMainScreen) return new BrandTitleScreen();
        return screen;
    }

    @Inject(method = "getWindowTitle", at = @At("RETURN"), cancellable = true)
    private void kizuna$windowTitle(CallbackInfoReturnable<String> cir) {
        if (Brand.active && !Brand.name.isEmpty()) cir.setReturnValue(Brand.name);
    }
}
