package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/** Small gains (XP, Marks, a kill) as a quiet pop by the crosshair, for players with the mod; false if they can't show it. */
public final class Pops {
    private Pops() { }

    public static boolean send(ServerPlayerEntity p, String kind, long amount, String label) {
        if (!ServerPlayNetworking.canSend(p, Net.Pop.ID)) return false;
        ServerPlayNetworking.send(p, new Net.Pop(kind, amount, label == null ? "" : label));
        return true;
    }
}
