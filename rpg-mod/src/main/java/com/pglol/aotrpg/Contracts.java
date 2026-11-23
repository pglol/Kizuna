package com.pglol.aotrpg;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mercenary board: contracts for coin.
 * <ul>
 *   <li><b>Wanted criminals</b>: a named outlaw (a bandit chief, a headsman for hire, a brute
 *   who broke out, a turncoat captain) and their crew, somewhere out there. Their last known
 *   whereabouts go on your map; the closer you get, the surer it is.</li>
 *   <li><b>Titan contracts</b>: an ogre seen out beyond the walls, or (for the strongest) an apex.</li>
 *   <li><b>Player bounties</b>: anyone can post a price on another player's head (the Marks are
 *   held until someone collects). Player killers go on the board by themselves: kill other
 *   players twice inside half an hour and there is a contract out on you.</li>
 * </ul>
 * Up to three at once. The board turns over every twenty minutes.
 */
public final class Contracts {
    private Contracts() {}

    public enum Kind { CRIMINAL, TITAN, PLAYER }

    public static final class Contract {
        final String id;
        final Kind kind;
        final String title, desc;
        final long reward;
        final int level;
        int x, z;
        final long until;
        /** The target once it's out there (a body, a titan, or the player). */
        UUID target;
        /** Who put up the Marks (a posted bounty), or null. */
        UUID poster;
        boolean spawned;

        Contract(String id, Kind kind, String title, String desc, long reward, int level, int x, int z, long until) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.desc = desc;
            this.reward = reward;
            this.level = level;
            this.x = x;
            this.z = z;
            this.until = until;
        }
    }

    private static MinecraftServer server;
    private static final List<Contract> board = new ArrayList<>();
    /** Contracts taken, by player (shared: a criminal is out there once, for whoever took it). */
    private static final Map<UUID, List<String>> taken = new HashMap<>();
    private static final Map<UUID, List<Long>> pks = new HashMap<>();
    private static long nextBoard;
    private static int seq;

    private static final String[] FIRST = {"Gustav", "Edda", "Rolf", "Hilde", "Viktor", "Marta", "Otto", "Greta", "Lorenz", "Ilse", "Bruno", "Sabine",
        "Kurt", "Frieda", "Anton", "Wilma", "Ruben", "Odile"};
    private static final String[] EPITHET = {"the Butcher", "Red Hand", "the Jackal", "Nine Fingers", "the Quiet", "Ash", "the Ferryman", "Hollow",
        "Iron Jaw", "the Widow", "Two Blades", "the Rat", "Last Breath", "the Debt"};

    private static Contract find(String id) {
        for (Contract c : board) if (c.id.equals(id)) return c;
        return null;
    }

    private static int levelNear(ServerPlayerEntity p) {
        return Math.max(1, AotRpg.PROFILES.get(p.getUuid()).level);
    }

    /** A spot out in the wilds, a fair way from here. */
    private static int[] wildSpot(Random r, double cx, double cz, int min, int max) {
        for (int t = 0; t < 20; t++) {
            double a = r.nextDouble() * Math.PI * 2, d = min + r.nextInt(max - min);
            int x = (int) (cx + Math.cos(a) * d), z = (int) (cz + Math.sin(a) * d);
            if (AotRpg.PLACES.nearest(x, z, 120, "town", "village", "city", "capital", "camp") != null) continue;
            Net.Area ar = AotRpg.PLACES.areaAt(x, z);
            if (ar != null && ar.look().equals("sea")) continue;
            return new int[] {x, z};
        }
        return null;
    }

    private static void refresh(long now) {
        nextBoard = now + 20 * 60_000;
        board.removeIf(c -> c.kind != Kind.PLAYER && !takenByAnyone(c.id) || now > c.until);
        Random r = server.getOverworld().random;
        List<ServerPlayerEntity> ps = server.getPlayerManager().getPlayerList();
        int top = 20;
        double cx = 0, cz = 0;
        for (ServerPlayerEntity p : ps) {
            top = Math.max(top, levelNear(p));
            cx += p.getX();
            cz += p.getZ();
        }
        if (!ps.isEmpty()) {
            cx /= ps.size();
            cz /= ps.size();
        }
        for (int i = 0; i < 4; i++) {
            int lv = Math.max(10, top - 10 + r.nextInt(16));
            int[] at = wildSpot(r, cx, cz, 500, 1600);
            if (at == null) continue;
            String name = FIRST[r.nextInt(FIRST.length)] + " \"" + EPITHET[r.nextInt(EPITHET.length)] + "\"";
            long reward = Math.round((900 + lv * 30L) / 50.0) * 50;
            board.add(new Contract("c" + (seq++), Kind.CRIMINAL, "Wanted: " + name, "Lv " + lv + " outlaw and crew. Last seen out in the wilds.", reward, lv,
                at[0], at[1], now + 45 * 60_000));
        }
        for (int i = 0; i < 2; i++) {
            boolean apex = top >= 80 && r.nextFloat() < 0.4f;
            int lv = Math.max(15, top - 4 + r.nextInt(8));
            int[] at = wildSpot(r, cx, cz, 600, 1800);
            if (at == null) continue;
            long reward = Math.round(((apex ? 2400 : 1500) + lv * 35L) / 50.0) * 50;
            board.add(new Contract("c" + (seq++), Kind.TITAN, apex ? "Apex Titan" : "Ogre Titan",
                (apex ? "Something wrong in the wilds, burning from inside. " : "A huge one, throwing rocks. ") + "Lv " + lv + ".", reward, lv, at[0], at[1],
                now + 45 * 60_000));
        }
    }

    private static boolean takenByAnyone(String id) {
        for (List<String> l : taken.values()) if (l.contains(id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the board, on screen

    public static void send(ServerPlayerEntity p) {
        send(p, false);
    }

    public static void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ContractView.ID)) return;
        long now = System.currentTimeMillis();
        List<String> mine = taken.getOrDefault(p.getUuid(), List.of());
        List<String> rows = new ArrayList<>();
        for (Contract c : board) {
            if (c.kind == Kind.PLAYER && p.getUuid().equals(c.target)) continue;
            long mins = Math.max(0, (c.until - now) / 60_000);
            rows.add(String.join("|", c.id, c.kind.name(), c.title, c.desc, Long.toString(c.reward), Integer.toString(c.level),
                mine.contains(c.id) ? "1" : "0", Long.toString(mins), c.poster != null && c.poster.equals(p.getUuid()) ? "1" : "0"));
        }
        ServerPlayNetworking.send(p, new Net.ContractView(rows, mine.size(), open));
    }

    public static void action(ServerPlayerEntity p, String action, String arg) {
        List<String> mine = taken.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
        switch (action) {
            case "open" -> { }
            case "take" -> {
                Contract c = find(arg);
                if (c == null || mine.contains(arg)) break;
                if (mine.size() >= 3) {
                    Notify.toast(p, Text.literal("Three at a time").formatted(Formatting.RED), Text.literal("Finish or drop one first"), 0xE04A3A);
                    break;
                }
                if (c.kind == Kind.PLAYER && p.getUuid().equals(c.target)) break;
                mine.add(arg);
                p.playSoundToPlayer(SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1f, 0.9f);
                Notify.toast(p, Text.literal(c.title).formatted(Formatting.GOLD), Text.literal("Contract taken · on your map"), 0xE0B96A);
                AotRpg.QUESTS.markers(p, true);
            }
            case "drop" -> {
                mine.remove(arg);
                AotRpg.QUESTS.markers(p, true);
            }
            case "post" -> {
                String[] f = arg.split("\\|");
                if (f.length < 2) break;
                long amt;
                try {
                    amt = Long.parseLong(f[1].trim());
                } catch (NumberFormatException e) {
                    break;
                }
                post(p, f[0].trim(), amt);
            }
            default -> { }
        }
        send(p);
    }

    private static ServerPlayerEntity byName(String name) {
        for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
            Profile pr = AotRpg.PROFILES.get(o.getUuid());
            if (o.getName().getString().equalsIgnoreCase(name) || pr.name != null && pr.name.equalsIgnoreCase(name)) return o;
        }
        return null;
    }

    private static String nameOf(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        return pr.created && pr.name != null && !pr.name.isBlank() ? pr.name : p.getName().getString();
    }

    private static void post(ServerPlayerEntity p, String who, long amt) {
        ServerPlayerEntity t = byName(who);
        if (t == null || t == p) {
            Notify.toast(p, Text.literal("No one by that name online").formatted(Formatting.RED), null, 0xE04A3A);
            return;
        }
        if (amt < 1000 || amt > 100_000) {
            Notify.toast(p, Text.literal("Between 1,000 and 100,000 Marks").formatted(Formatting.RED), null, 0xE04A3A);
            return;
        }
        if (!AotRpg.WALLET.spendMarks(p, amt)) {
            Notify.toast(p, Text.literal("Not enough Marks").formatted(Formatting.RED), null, 0xE04A3A);
            return;
        }
        Contract c = new Contract("c" + (seq++), Kind.PLAYER, "Bounty: " + nameOf(t), "Posted by " + nameOf(p) + ". Paid on the kill.", amt,
            levelNear(t), t.getBlockX(), t.getBlockZ(), System.currentTimeMillis() + 60 * 60_000);
        c.target = t.getUuid();
        c.poster = p.getUuid();
        c.spawned = true;
        board.add(c);
        Notify.toast(p, Text.literal("Bounty posted").formatted(Formatting.GOLD), Text.literal(String.format("%,d", amt) + " Marks on " + nameOf(t)), 0xE0B96A);
        Notify.toast(t, Text.literal("A price on your head").formatted(Formatting.RED, Formatting.BOLD), Text.literal(String.format("%,d", amt) + " Marks"), 0xE04A3A);
    }

    // ------------------------------------------------------------------ out in the world

    private static void tick(long now) {
        if (now >= nextBoard) refresh(now);
        ServerWorld w = server.getOverworld();
        for (Contract c : new ArrayList<>(board)) {
            if (now > c.until) {
                expire(w, c);
                continue;
            }
            if (c.kind == Kind.PLAYER) {
                ServerPlayerEntity t = server.getPlayerManager().getPlayer(c.target);
                if (t != null) {
                    // Roughly where they are, a minute or so ago.
                    Random r = w.random;
                    c.x = t.getBlockX() + r.nextInt(161) - 80;
                    c.z = t.getBlockZ() + r.nextInt(161) - 80;
                }
                continue;
            }
            if (c.spawned || !takenByAnyone(c.id)) continue;
            // Someone who took it gets close: it's there.
            boolean near = false;
            for (ServerPlayerEntity p : w.getPlayers()) if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) < 140) near = true;
            if (!near || !w.isChunkLoaded(c.x >> 4, c.z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.x, c.z);
            c.spawned = true;
            if (c.kind == Kind.TITAN) {
                Entity t = Elites.spawn(w, c.x + 0.5, y, c.z + 0.5, c.title.startsWith("Apex"), c.level);
                if (t != null) c.target = t.getUuid();
            } else {
                BlockPos at = new BlockPos(c.x, y, c.z);
                Random r = w.random;
                Troops.Arch[] kinds = {Troops.Arch.BANDIT, Troops.Arch.HEADSMAN, Troops.Arch.BRUTE, Troops.Arch.MERCENARY, Troops.Arch.DESERTER};
                Troops.Arch k = kinds[r.nextInt(kinds.length)];
                String ses = "contract:" + c.id;
                Troops.group(w, at, 1, c.level + 3, ses, at, 60, r, k, 5);
                Troops.group(w, at, 2 + r.nextInt(3), c.level, ses, at, 60, r, k == Troops.Arch.HEADSMAN || k == Troops.Arch.BRUTE ? Troops.Arch.BANDIT : k, 3);
                List<VillagerEntity> crew = Troops.bodies(w, ses);
                if (!crew.isEmpty()) {
                    VillagerEntity boss = crew.get(0);
                    c.target = boss.getUuid();
                    var hp = boss.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
                    if (hp != null) hp.setBaseValue(hp.getBaseValue() * 1.8);
                    boss.setHealth(boss.getMaxHealth());
                    boss.setCustomName(Text.literal("Lv " + (c.level + 3) + " ").formatted(Formatting.GRAY)
                        .append(Text.literal(c.title.replace("Wanted: ", "")).formatted(Formatting.RED)));
                    // A name like that is known on sight: they know why you've come.
                    for (String id : List.copyOf(holders(c.id))) Troops.provokeAll(ses, UUID.fromString(id));
                }
            }
            // Now it's really there: the marker is exact.
            for (ServerPlayerEntity p : w.getPlayers()) if (taken.getOrDefault(p.getUuid(), List.of()).contains(c.id)) AotRpg.QUESTS.markers(p, true);
        }
    }

    private static List<String> holders(String id) {
        List<String> out = new ArrayList<>();
        for (var e : taken.entrySet()) if (e.getValue().contains(id)) out.add(e.getKey().toString());
        return out;
    }

    private static void expire(ServerWorld w, Contract c) {
        board.remove(c);
        for (List<String> l : taken.values()) l.remove(c.id);
        if (c.kind == Kind.CRIMINAL) Troops.clear(w, "contract:" + c.id);
        if (c.kind == Kind.TITAN && c.target != null) {
            Entity e = w.getEntity(c.target);
            if (e != null) e.discard();
        }
        // An unclaimed bounty goes back to whoever posted it.
        if (c.kind == Kind.PLAYER && c.poster != null) {
            ServerPlayerEntity po = server.getPlayerManager().getPlayer(c.poster);
            if (po != null) AotRpg.WALLET.addMarks(po, c.reward, "Bounty unclaimed");
        }
    }

    /** Paid: to whoever holds the contract and did it (or was there when it was done). */
    private static void pay(Contract c, ServerPlayerEntity killer, Vec3d where) {
        board.remove(c);
        List<ServerPlayerEntity> paid = new ArrayList<>();
        for (String id : holders(c.id)) {
            ServerPlayerEntity h = server.getPlayerManager().getPlayer(UUID.fromString(id));
            if (h == null) continue;
            if (h == killer || h.squaredDistanceTo(where) < 80 * 80) paid.add(h);
        }
        // A player bounty pays whoever collects it, contract or not.
        if (c.kind == Kind.PLAYER && killer != null && !paid.contains(killer)) paid.add(killer);
        for (List<String> l : taken.values()) l.remove(c.id);
        if (paid.isEmpty()) return;
        long each = Math.round(c.reward * Weekly.contracts()) / paid.size();
        for (ServerPlayerEntity h : paid) {
            AotRpg.WALLET.earn(h, each, "Contract");
            AotRpg.PROGRESSION.award(h, 300 + c.level * 12L, "Contract");
            AotRpg.SEASON.xp(h, 500);
            Reveal.show(h, "CONTRACT FULFILLED", c.title + " · +" + String.format("%,d", each) + " Marks", "minecraft:writable_book", 3);
            AotRpg.QUESTS.markers(h, true);
        }
    }

    public static void onTitanKill(ServerPlayerEntity killer, LivingEntity dead) {
        for (Contract c : new ArrayList<>(board)) if (c.kind == Kind.TITAN && dead.getUuid().equals(c.target)) pay(c, killer, dead.getPos());
    }

    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        List<String> mine = taken.getOrDefault(p.getUuid(), List.of());
        for (Contract c : board) {
            if (!mine.contains(c.id)) continue;
            String label = c.title + (c.spawned || c.kind == Kind.PLAYER ? "" : " (last seen)");
            int x = c.x, z = c.z;
            if (c.spawned && c.target != null && c.kind != Kind.PLAYER) {
                Entity e = server.getOverworld().getEntity(c.target);
                if (e != null) {
                    x = e.getBlockX();
                    z = e.getBlockZ();
                }
            }
            list.add(new Net.Marker("event", label, x, 70, z, c.kind == Kind.PLAYER ? 0xE04A3A : c.kind == Kind.TITAN ? 0xB06AE0 : 0xE0B96A));
        }
    }

    // ------------------------------------------------------------------ wiring

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            board.clear();
            taken.clear();
            nextBoard = 0;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 40 == 11 && server != null) tick(System.currentTimeMillis());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (server == null) return;
            ServerPlayerEntity killer = src.getAttacker() instanceof ServerPlayerEntity k ? k : null;
            for (Contract c : new ArrayList<>(board)) {
                if (c.target == null || !c.target.equals(e.getUuid())) continue;
                if (c.kind == Kind.CRIMINAL) {
                    Troops.clear((ServerWorld) e.getWorld(), "contract:" + c.id);
                    pay(c, killer, e.getPos());
                } else if (c.kind == Kind.PLAYER && killer != null && killer != e) pay(c, killer, e.getPos());
            }
            // Player killers: twice in half an hour and there's a contract out on you.
            if (e instanceof ServerPlayerEntity dead && killer != null && killer != dead
                && !Arenas.inMatch(killer) && !AotRpg.DUELS.dueling(killer.getUuid(), dead.getUuid())) {
                long now = System.currentTimeMillis();
                List<Long> l = pks.computeIfAbsent(killer.getUuid(), k -> new ArrayList<>());
                l.add(now);
                // Preying on someone far below you counts double: one such kill is enough to be posted.
                if (AotRpg.PROFILES.get(dead.getUuid()).level <= AotRpg.PROFILES.get(killer.getUuid()).level - 20) l.add(now);
                l.removeIf(t -> now - t > 30 * 60_000);
                boolean already = false;
                for (Contract c : board) if (c.kind == Kind.PLAYER && c.poster == null && killer.getUuid().equals(c.target)) already = true;
                if (l.size() >= 2 && !already) {
                    long reward = 1500 + 750L * (l.size() - 2) + levelNear(killer) * 20L;
                    Contract c = new Contract("c" + (seq++), Kind.PLAYER, "Player Killer: " + nameOf(killer),
                        l.size() + " players killed lately. The board pays to stop it.", reward, levelNear(killer), killer.getBlockX(), killer.getBlockZ(),
                        now + 40 * 60_000);
                    c.target = killer.getUuid();
                    c.spawned = true;
                    board.add(c);
                    Notify.toast(killer, Text.literal("You're on the board").formatted(Formatting.RED, Formatting.BOLD),
                        Text.literal("A contract is out on you for those kills"), 0xE04A3A);
                    for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                        if (o != killer) Notify.toast(o, Text.literal("New contract").formatted(Formatting.GOLD), Text.literal(c.title), 0xE0B96A);
                    }
                }
            }
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(CommandManager.literal("contracts").executes(ctx -> {
                send(ctx.getSource().getPlayerOrThrow(), true);
                return 1;
            }));
            d.register(CommandManager.literal("bounty")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("marks", IntegerArgumentType.integer(1000, 100_000)).executes(ctx -> {
                        post(ctx.getSource().getPlayerOrThrow(), StringArgumentType.getString(ctx, "player"), IntegerArgumentType.getInteger(ctx, "marks"));
                        return 1;
                    }))));
        });
    }
}
