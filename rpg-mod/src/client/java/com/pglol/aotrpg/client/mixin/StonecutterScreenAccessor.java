package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.gui.screen.ingame.StonecutterScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StonecutterScreen.class)
public interface StonecutterScreenAccessor {
    @Accessor("scrollOffset")
    int aotrpg$scrollOffset();

    @Accessor("scrollAmount")
    float aotrpg$scrollAmount();
}
