package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ClientState;
import com.pglol.aotrpg.client.RpgInventoryScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import com.pglol.aotrpg.client.AotOptionsScreen;
import com.pglol.aotrpg.client.ControlsScreen;
import com.pglol.aotrpg.client.AotPauseScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import com.pglol.aotrpg.client.AotDeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the RPG inventory instead of the vanilla survival inventory. */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void aotrpg$inventory(Screen screen, CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient) (Object) this;
        if (screen != null && screen.getClass() == InventoryScreen.class && mc.player != null && ClientState.profile != null) {
            ci.cancel();
            mc.setScreen(new RpgInventoryScreen(mc.player));
            return;
        }
        // Our Options and Controls in place of Minecraft's (title screen and in game). Coming back from
        // one of Minecraft's own option pages to its options screen is left alone.
        if (screen != null && screen.getClass() == OptionsScreen.class) {
            if (AotOptionsScreen.vanilla) {
                AotOptionsScreen.vanilla = false;
            } else if (!(mc.currentScreen instanceof GameOptionsScreen)) {
                ci.cancel();
                mc.setScreen(AotOptionsScreen.create(mc.currentScreen));
                return;
            }
        }
        if (screen != null && screen.getClass() == ControlsOptionsScreen.class && !(mc.currentScreen instanceof GameOptionsScreen)) {
            ci.cancel();
            mc.setScreen(new ControlsScreen(mc.currentScreen, false));
            return;
        }
        if (screen != null && screen.getClass() == GameMenuScreen.class) {
            ci.cancel();
            mc.setScreen(new AotPauseScreen());
            return;
        }
        // The death screen (also when something closes it while still dead).
        boolean dead = mc.player != null && mc.player.isDead() && mc.player.showsDeathScreen();
        if (ClientState.profile != null && (screen instanceof DeathScreen || (screen == null && dead && mc.world != null))) {
            ci.cancel();
            mc.setScreen(new AotDeathScreen());
        }
    }
}
