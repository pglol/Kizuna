package net.pglol.kata;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * What a player's body is doing beyond walking (vaulting, hanging, climbing), so everyone sees it.
 * The player's own game decides (movement is worked out there); the server passes it to whoever can
 * see them.
 */
public final class KataNet {
    private KataNet() {}

    /** Client -> server: my parkour move changed. mode: see Parkour.Mode; side: -1, 0, 1 (shuffling). */
    public record Act(int mode, int side) implements CustomPayload {
        public static final Id<Act> ID = new Id<>(Identifier.of(KataCore.ID, "act"));
        public static final PacketCodec<RegistryByteBuf, Act> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.mode);
            b.writeByte(v.side);
        }, b -> new Act(b.readVarInt(), b.readByte()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: that player's parkour move changed. */
    public record ActOf(int entity, int mode, int side) implements CustomPayload {
        public static final Id<ActOf> ID = new Id<>(Identifier.of(KataCore.ID, "act_of"));
        public static final PacketCodec<RegistryByteBuf, ActOf> CODEC = PacketCodec.of((v, b) -> {
            b.writeVarInt(v.entity);
            b.writeVarInt(v.mode);
            b.writeByte(v.side);
        }, b -> new ActOf(b.readVarInt(), b.readVarInt(), b.readByte()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Act mode meaning "crouched to catch a landing" (for the server's fall damage; not shown). */
    public static final int BRACE = 31;

    static void register() {
        PayloadTypeRegistry.playC2S().register(Act.ID, Act.CODEC);
        PayloadTypeRegistry.playS2C().register(ActOf.ID, ActOf.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Act.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (payload.mode() == BRACE) {
                KataCore.brace(p);
                return;
            }
            int mode = Math.max(0, Math.min(15, payload.mode())), side = Math.max(-1, Math.min(1, payload.side()));
            for (ServerPlayerEntity other : PlayerLookup.tracking(p)) {
                if (other != p && ServerPlayNetworking.canSend(other, ActOf.ID)) ServerPlayNetworking.send(other, new ActOf(p.getId(), mode, side));
            }
        });
    }
}
