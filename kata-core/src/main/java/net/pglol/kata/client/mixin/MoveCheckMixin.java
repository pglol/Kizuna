package net.pglol.kata.client.mixin;

import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks while the server is checking a player's movement (on its own thread): KataCore.moveCheck, for AirMoveMixin. */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class MoveCheckMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method = "onPlayerMove", at = @At("HEAD"), require = 0)
    private void kata$begin(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        // The packet arrives first on the network thread (and is handed over); only the server thread's pass checks it.
        if (player != null && player.getServer() != null && player.getServer().isOnThread()) net.pglol.kata.KataCore.moveCheck = true;
    }

    @Inject(method = "onPlayerMove", at = @At("RETURN"), require = 0)
    private void kata$end(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        net.pglol.kata.KataCore.moveCheck = false;
    }
}
