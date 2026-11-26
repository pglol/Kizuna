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

    /** Client -> server: a fighting action. kind GUARD (a: 1 on, 0 off), STRIKE (a: move, b: target entity or -1), STANCE (a: stance). */
    public record Fight(int kind, int a, int b) implements CustomPayload {
        public static final Id<Fight> ID = new Id<>(Identifier.of(KataCore.ID, "fight"));
        public static final PacketCodec<RegistryByteBuf, Fight> CODEC = PacketCodec.of((v, buf) -> {
            buf.writeVarInt(v.kind);
            buf.writeVarInt(v.a);
            buf.writeVarInt(v.b);
        }, buf -> new Fight(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server -> client: that player's fighting action (to show it), or GUARD_HIT to the guard (a: side, 1 right, -1 left). */
    public record FightOf(int entity, int kind, int a) implements CustomPayload {
        public static final Id<FightOf> ID = new Id<>(Identifier.of(KataCore.ID, "fight_of"));
        public static final PacketCodec<RegistryByteBuf, FightOf> CODEC = PacketCodec.of((v, buf) -> {
            buf.writeVarInt(v.entity);
            buf.writeVarInt(v.kind);
            buf.writeVarInt(v.a);
        }, buf -> new FightOf(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static final int GUARD = 0, STRIKE = 1, STANCE = 2, GUARD_HIT = 3, GUARD_BROKEN = 4, WINDUP = 5, STAGGER = 6, PARRY = 7, KNOCKDOWN = 8, DUMMY_REST = 9, DUMMY_AT = 10, HIT = 11, BLOCKED = 12, DODGE = 13, POISE = 14, FLINCH = 15, PARRIED = 16, EVADED = 17;

    /** Act mode meaning "crouched to catch a landing" (for the server's fall damage; not shown). */
    public static final int BRACE = 31;
    /** Act mode meaning "slammed into a wall"; side carries the speed (twentieths of a block a tick). */
    public static final int IMPACT = 32;

    static void register() {
        PayloadTypeRegistry.playC2S().register(Act.ID, Act.CODEC);
        PayloadTypeRegistry.playS2C().register(ActOf.ID, ActOf.CODEC);
        PayloadTypeRegistry.playC2S().register(Fight.ID, Fight.CODEC);
        PayloadTypeRegistry.playS2C().register(FightOf.ID, FightOf.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Fight.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            switch (payload.kind()) {
                case GUARD -> Fighting.guard(p, payload.a());
                case DODGE -> Fighting.dodge(p, payload.a());
                case STRIKE -> Fighting.strike(p, payload.a(), payload.b());
                case STANCE -> Fighting.stance(p, payload.a());
                case WINDUP -> Fighting.windup(p, payload.a(), payload.b());
                case DUMMY_REST -> Fighting.dummyRest(p, payload.a(), payload.b(), true);
                case DUMMY_AT -> Fighting.dummyRest(p, payload.a(), payload.b(), false);
                default -> { }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Act.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            if (payload.mode() == BRACE) {
                KataCore.brace(p);
                return;
            }
            if (payload.mode() == IMPACT) {
                KataCore.impact(p, payload.side() / 20.0);
                return;
            }
            int mode = Math.max(0, Math.min(15, payload.mode())), side = Math.max(-1, Math.min(1, payload.side()));
            for (ServerPlayerEntity other : PlayerLookup.tracking(p)) {
                if (other != p && ServerPlayNetworking.canSend(other, ActOf.ID)) ServerPlayNetworking.send(other, new ActOf(p.getId(), mode, side));
            }
        });
    }
}
