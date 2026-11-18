package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blade locks: clash with the same fighter twice running and the blades bind. Both held face to
 * face, steel grinding, for two and a half seconds: strike as fast as you can. Strength and
 * stamina count too. The winner throws the other off, stunned and flying.
 */
public final class Clashes {
    private Clashes() { }

    private static final class Lock {
        final ServerPlayerEntity a, b;
        final Vec3d atA, atB;
        int left = 50, pa, pb;

        Lock(ServerPlayerEntity a, ServerPlayerEntity b) {
            this.a = a;
            this.b = b;
            this.atA = a.getPos();
            this.atB = b.getPos();
        }

        float power(ServerPlayerEntity p, int presses) {
            Profile pr = AotRpg.PROFILES.get(p.getUuid());
            return presses + pr.total(Stat.STRENGTH) * 0.6f + AotRpg.STAMINA.fraction(p.getUuid()) * 3;
        }

        /** -1 (a losing) .. 1 (a winning). */
        float balance() {
            float x = power(a, pa), y = power(b, pb);
            return x + y < 0.01f ? 0 : (x - y) / (x + y);
        }
    }

    private static final Map<String, long[]> recent = new HashMap<>();
    private static final List<Lock> locks = new ArrayList<>();

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(Net.ClashMash.ID, (payload, ctx) -> {
            Lock l = of(ctx.player());
            if (l == null) return;
            int n = Math.max(0, Math.min(60, payload.presses()));
            if (l.a == ctx.player()) l.pa = Math.max(l.pa, n);
            else l.pb = Math.max(l.pb, n);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            !world.isClient && player instanceof ServerPlayerEntity sp && of(sp) != null ? ActionResult.FAIL : ActionResult.PASS);
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
    }

    private static Lock of(ServerPlayerEntity p) {
        for (Lock l : locks) if (l.a == p || l.b == p) return l;
        return null;
    }

    public static boolean locked(ServerPlayerEntity p) {
        return of(p) != null;
    }

    private static String key(ServerPlayerEntity a, ServerPlayerEntity b) {
        String x = a.getUuidAsString(), y = b.getUuidAsString();
        return x.compareTo(y) < 0 ? x + y : y + x;
    }

    /** A clash between these two: true if it binds into a blade lock (the second in a row). */
    public static boolean clash(ServerPlayerEntity a, ServerPlayerEntity b) {
        if (of(a) != null || of(b) != null) return false;
        long now = System.currentTimeMillis();
        String k = key(a, b);
        long[] r = recent.get(k);
        if (r == null || now - r[1] > 15000) r = new long[] {0, now};
        r[0]++;
        r[1] = now;
        recent.put(k, r);
        if (recent.size() > 256) recent.values().removeIf(v -> now - v[1] > 20_000);
        if (r[0] < 2) return false;
        recent.remove(k);
        Lock l = new Lock(a, b);
        locks.add(l);
        ServerWorld w = a.getServerWorld();
        w.playSound(null, a.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1f, 0.7f);
        w.playSound(null, a.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1f, 0.5f);
        Impact.frame(w, a.getEyePos().add(b.getEyePos()).multiply(0.5), 0.8f, a, b);
        send(l);
        return true;
    }

    private static void send(Lock l) {
        float bal = l.balance();
        if (ServerPlayNetworking.canSend(l.a, Net.ClashLock.ID)) ServerPlayNetworking.send(l.a, new Net.ClashLock(l.b.getId(), l.left, bal));
        if (ServerPlayNetworking.canSend(l.b, Net.ClashLock.ID)) ServerPlayNetworking.send(l.b, new Net.ClashLock(l.a.getId(), l.left, -bal));
    }

    private static void tick() {
        if (locks.isEmpty()) return;
        for (Lock l : new ArrayList<>(locks)) {
            if (!l.a.isAlive() || !l.b.isAlive() || l.a.isRemoved() || l.b.isRemoved() || l.a.getWorld() != l.b.getWorld()) {
                locks.remove(l);
                continue;
            }
            ServerWorld w = l.a.getServerWorld();
            // Held face to face where they met.
            for (ServerPlayerEntity p : new ServerPlayerEntity[] {l.a, l.b}) {
                Vec3d at = p == l.a ? l.atA : l.atB;
                if (p.squaredDistanceTo(at) > 0.09) p.requestTeleport(at.x, at.y, at.z);
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
            }
            Vec3d mid = l.a.getEyePos().add(l.b.getEyePos()).multiply(0.5).subtract(0, 0.2, 0);
            if (l.left % 3 == 0) {
                AotParticles.burst(w, AotParticles.SPARK, 0xFFE08A, 0.45f, mid.x, mid.y, mid.z, 4, 0.12, 0.35);
                w.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.PLAYERS, 0.5f, 1.4f + l.balance() * 0.3f);
            }
            if (l.left % 5 == 0) send(l);
            if (--l.left > 0) continue;
            locks.remove(l);
            float bal = l.balance();
            ServerPlayerEntity win = bal >= 0 ? l.a : l.b, lose = win == l.a ? l.b : l.a;
            if (ServerPlayNetworking.canSend(l.a, Net.ClashLock.ID)) ServerPlayNetworking.send(l.a, new Net.ClashLock(l.b.getId(), 0, bal));
            if (ServerPlayNetworking.canSend(l.b, Net.ClashLock.ID)) ServerPlayNetworking.send(l.b, new Net.ClashLock(l.a.getId(), 0, -bal));
            Stuns.stun(lose, 30);
            Impact.launch(lose, win, 1.8);
            Adrenaline.gain(win, 20);
            AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 7f), mid.x, mid.y, mid.z, 0, 0, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFD24A, 5f), mid.x, mid.y, mid.z, 0, 0, 0);
            AotParticles.burst(w, AotParticles.SHARD, 0xE8E8F0, 0.45f, mid.x, mid.y, mid.z, 18, 0.2, 0.7);
            Impact.frame(w, mid, 1.2f, win, lose);
            w.playSound(null, mid.x, mid.y, mid.z, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.2f, 0.7f);
            for (ServerPlayerEntity o : PlayerLookup.around(w, mid, 20)) if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(0.4f, 8));
            win.sendMessage(Text.literal("BLADE LOCK WON").formatted(Formatting.GOLD, Formatting.BOLD), true);
            lose.sendMessage(Text.literal("Overpowered").formatted(Formatting.RED, Formatting.BOLD), true);
        }
    }

    public static void forget(UUID id) {
        locks.removeIf(l -> l.a.getUuid().equals(id) || l.b.getUuid().equals(id));
    }
}
