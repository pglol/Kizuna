package com.pglol.aotrpg.client.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int aotrpg$x();

    @Accessor("y")
    int aotrpg$y();

    @Accessor("backgroundWidth")
    int aotrpg$w();

    @Accessor("backgroundHeight")
    int aotrpg$h();

    @Accessor("titleX")
    int aotrpg$titleX();

    @Accessor("titleY")
    int aotrpg$titleY();

    @Accessor("playerInventoryTitleX")
    int aotrpg$invX();

    @Accessor("playerInventoryTitleY")
    int aotrpg$invY();

    @Accessor("playerInventoryTitle")
    Text aotrpg$invTitle();
}
