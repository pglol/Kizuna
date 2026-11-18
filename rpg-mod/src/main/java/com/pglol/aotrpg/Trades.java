package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trading between players, anywhere on the server: ask someone, they accept, each of you puts
 * up items from your inventory (and Marks), both press Ready, and it all changes hands at once.
 * Changing an offer takes both Readys back; nothing moves unless both still have what they
 * offered.
 */
public final class Trades {
    private Trades() { }

    private static final class Offer {
        final List<int[]> slots = new ArrayList<>();
        final List<ItemStack> snap = new ArrayList<>();
        long marks;
        boolean ready;
    }

    private static final class Session {
        final UUID a, b;
        boolean active;
        final Map<UUID, Offer> offers = new HashMap<>();
        final long at = System.currentTimeMillis();

        Session(UUID a, UUID b) {
            this.a = a;
            this.b = b;
            offers.put(a, new Offer());
            offers.put(b, new Offer());
        }

        UUID other(UUID id) {
            return id.equals(a) ? b : a;
        }
    }

    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static MinecraftServer server;

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            server = s;
            if (s.getTicks() % 20 != 0 || sessions.isEmpty()) return;
            for (Session t : new ArrayList<>(sessions.values())) {
                ServerPlayerEntity pa = s.getPlayerManager().getPlayer(t.a), pb = s.getPlayerManager().getPlayer(t.b);
                // Someone left, or an unanswered request after a minute: it's off.
                if (pa == null || pb == null || !t.active && System.currentTimeMillis() - t.at > 60_000) end(t, "The trade was called off");
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.TradeAction.ID, (payload, ctx) -> action(ctx.player(), payload.action(), payload.target(), payload.data()));
    }

    private static ServerPlayerEntity player(UUID id) {
        return server == null ? null : server.getPlayerManager().getPlayer(id);
    }

    private static String name(UUID id) {
        return AotRpg.PROFILES.get(id).name;
    }

    private static String busy(ServerPlayerEntity p) {
        if (Extraction.inRun(p.getUuid())) return "out on a run";
        if (AotRpg.DUELS.isDueling(p)) return "in a duel";
        return null;
    }

    public static void action(ServerPlayerEntity p, String action, UUID target, String data) {
        if (!AotRpg.PROFILES.get(p.getUuid()).created) return;
        server = p.getServer();
        Session t = sessions.get(p.getUuid());
        switch (action) {
            case "request" -> {
                ServerPlayerEntity o = target == null ? null : player(target);
                if (o == null || o == p) return;
                String why = busy(p) != null ? "You're " + busy(p) : busy(o) != null ? name(o.getUuid()) + " is " + busy(o) : null;
                if (why == null && sessions.containsKey(o.getUuid()) && sessions.get(o.getUuid()) != t) why = name(o.getUuid()) + " is already trading";
                if (why != null) {
                    p.sendMessage(Text.literal(why).formatted(Formatting.GRAY), true);
                    return;
                }
                // They'd already asked you: that's a yes.
                if (t != null && !t.active && t.a.equals(o.getUuid())) {
                    t.active = true;
                    send(t, true);
                    return;
                }
                if (t != null) end(t, "");
                Session s = new Session(p.getUuid(), o.getUuid());
                sessions.put(p.getUuid(), s);
                sessions.put(o.getUuid(), s);
                o.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.MASTER, 0.8f, 1.4f);
                send(s, true);
            }
            case "accept" -> {
                if (t == null || t.active || !t.b.equals(p.getUuid())) return;
                t.active = true;
                send(t, true);
            }
            case "decline", "cancel" -> {
                if (t != null) end(t, name(p.getUuid()) + " called off the trade");
            }
            case "offer" -> {
                if (t == null || !t.active) return;
                Offer of = t.offers.get(p.getUuid());
                of.slots.clear();
                of.snap.clear();
                String[] parts = data.split(";", 2);
                var inv = p.getInventory();
                if (!parts[0].isBlank()) for (String e : parts[0].split(",")) {
                    String[] sc = e.split(":");
                    try {
                        int slot = Integer.parseInt(sc[0]), n = Integer.parseInt(sc[1]);
                        if (slot < 0 || slot >= 36 || of.slots.size() >= 9) continue;
                        ItemStack st = inv.getStack(slot);
                        if (st.isEmpty()) continue;
                        boolean dup = false;
                        for (int[] q : of.slots) if (q[0] == slot) dup = true;
                        if (dup) continue;
                        n = Math.max(1, Math.min(n, st.getCount()));
                        of.slots.add(new int[] {slot, n});
                        of.snap.add(st.copyWithCount(n));
                    } catch (Exception ignored) { }
                }
                try {
                    of.marks = parts.length > 1 ? Math.max(0, Math.min(Long.parseLong(parts[1].trim()), AotRpg.WALLET.marks(p))) : 0;
                } catch (Exception e) {
                    of.marks = 0;
                }
                // Any change: both have to look again.
                for (Offer x : t.offers.values()) x.ready = false;
                send(t, false);
            }
            case "ready" -> {
                if (t == null || !t.active) return;
                Offer of = t.offers.get(p.getUuid());
                of.ready = !of.ready;
                if (t.offers.get(t.a).ready && t.offers.get(t.b).ready) complete(t);
                else send(t, false);
            }
            case "open" -> {
                if (t != null) send(t, true);
            }
            default -> { }
        }
    }

    /** Both ready: check everything's still there, then swap it all at once. */
    private static void complete(Session t) {
        ServerPlayerEntity pa = player(t.a), pb = player(t.b);
        if (pa == null || pb == null) {
            end(t, "The trade was called off");
            return;
        }
        for (ServerPlayerEntity p : List.of(pa, pb)) {
            Offer of = t.offers.get(p.getUuid());
            for (int i = 0; i < of.slots.size(); i++) {
                ItemStack now = p.getInventory().getStack(of.slots.get(i)[0]);
                if (!ItemStack.areItemsAndComponentsEqual(now, of.snap.get(i)) || now.getCount() < of.slots.get(i)[1]) {
                    for (Offer x : t.offers.values()) x.ready = false;
                    pa.sendMessage(Text.literal("Something offered moved: check the trade again").formatted(Formatting.RED), true);
                    pb.sendMessage(Text.literal("Something offered moved: check the trade again").formatted(Formatting.RED), true);
                    send(t, false);
                    return;
                }
            }
            if (AotRpg.WALLET.marks(p) < of.marks) {
                for (Offer x : t.offers.values()) x.ready = false;
                send(t, false);
                return;
            }
        }
        // Take from both first, then hand over.
        Map<UUID, List<ItemStack>> taken = new HashMap<>();
        for (ServerPlayerEntity p : List.of(pa, pb)) {
            Offer of = t.offers.get(p.getUuid());
            List<ItemStack> out = new ArrayList<>();
            for (int[] q : of.slots) out.add(p.getInventory().getStack(q[0]).split(q[1]));
            taken.put(p.getUuid(), out);
            if (of.marks > 0) AotRpg.WALLET.spendMarks(p, of.marks);
        }
        for (ServerPlayerEntity p : List.of(pa, pb)) {
            UUID from = t.other(p.getUuid());
            for (ItemStack s : taken.get(from)) p.getInventory().offerOrDrop(s);
            long m = t.offers.get(from).marks;
            if (m > 0) AotRpg.WALLET.addMarks(p, m, "Trade with " + name(from));
            p.playSoundToPlayer(SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.MASTER, 0.8f, 1.1f);
            Notify.toast(p, Text.literal("Trade complete").formatted(Formatting.GOLD), Text.literal("with " + name(from)), 0xE0B96A);
        }
        sessions.remove(t.a);
        sessions.remove(t.b);
        for (ServerPlayerEntity p : List.of(pa, pb)) close(p, 4);
    }

    private static void end(Session t, String why) {
        sessions.remove(t.a);
        sessions.remove(t.b);
        for (UUID id : List.of(t.a, t.b)) {
            ServerPlayerEntity p = player(id);
            if (p == null) continue;
            if (!why.isEmpty()) p.sendMessage(Text.literal(why).formatted(Formatting.GRAY), true);
            close(p, 0);
        }
    }

    private static void close(ServerPlayerEntity p, int state) {
        if (ServerPlayNetworking.canSend(p, Net.TradeView.ID)) {
            ServerPlayNetworking.send(p, new Net.TradeView(state, "", null, List.of(), List.of(), 0, false, List.of(), 0, false, false));
        }
    }

    private static void send(Session t, boolean open) {
        for (UUID id : List.of(t.a, t.b)) {
            ServerPlayerEntity p = player(id);
            if (p == null || !ServerPlayNetworking.canSend(p, Net.TradeView.ID)) continue;
            UUID o = t.other(id);
            Offer mine = t.offers.get(id), theirs = t.offers.get(o);
            List<Integer> slots = new ArrayList<>();
            for (int[] q : mine.slots) slots.add(q[0]);
            int state = t.active ? 3 : id.equals(t.b) ? 1 : 2;
            ServerPlayNetworking.send(p, new Net.TradeView(state, name(o), o, new ArrayList<>(mine.snap), slots, mine.marks, mine.ready,
                new ArrayList<>(theirs.snap), theirs.marks, theirs.ready, open));
        }
    }
}
