package com.pglol.aotrpg.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerMetadata;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The server-list ping lists everyone online (not Minecraft's random 12), so the launcher's friends
 * list can show who's on. Players who use /vanish-style hiding aren't special-cased: the vanilla
 * "hide-online-players" setting still empties the list.
 */
@Mixin(MinecraftServer.class)
public abstract class StatusPlayersMixin {
    @Inject(method = "createMetadataPlayers", at = @At("RETURN"), cancellable = true, require = 0)
    private void aotrpg$allPlayers(CallbackInfoReturnable<ServerMetadata.Players> cir) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        if (self.hideOnlinePlayers()) return;
        ServerMetadata.Players p = cir.getReturnValue();
        List<GameProfile> all = new ArrayList<>();
        for (ServerPlayerEntity sp : self.getPlayerManager().getPlayerList()) {
            all.add(sp.getGameProfile());
            if (all.size() >= 200) break;
        }
        cir.setReturnValue(new ServerMetadata.Players(p.max(), p.online(), all));
    }
}
