package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.ContainerSkin;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.client.gui.screen.ingame.BeaconScreen;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.EnchantmentScreen;
import net.minecraft.client.gui.screen.ingame.ForgingScreen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.HorseScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every vanilla screen with slots, in the house style (each with its own working parts redrawn). */
@Mixin({GenericContainerScreen.class, ShulkerBoxScreen.class, HopperScreen.class, Generic3x3ContainerScreen.class, AbstractFurnaceScreen.class,
    BrewingStandScreen.class, CraftingScreen.class, EnchantmentScreen.class, ForgingScreen.class, GrindstoneScreen.class, StonecutterScreen.class,
    MerchantScreen.class, BeaconScreen.class, HorseScreen.class})
public abstract class ContainerSkinMixin {
    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void aotrpg$skin(DrawContext c, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        ContainerSkin.background((HandledScreen<?>) (Object) this, c, mouseX, mouseY);
        ci.cancel();
    }
}
