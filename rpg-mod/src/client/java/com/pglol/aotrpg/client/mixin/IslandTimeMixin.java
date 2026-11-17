package com.pglol.aotrpg.client.mixin;

import com.pglol.aotrpg.client.IslandSky;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** On an island its own hour holds: the server's clock updates can't flick the sky back for a frame. */
@Mixin(ClientWorld.class)
public class IslandTimeMixin {
    @ModifyVariable(method = "setTimeOfDay", at = @At("HEAD"), argsOnly = true)
    private long aotrpg$islandHour(long time) {
        Long t = IslandSky.hour((ClientWorld) (Object) this);
        return t == null ? time : t;
    }
}
