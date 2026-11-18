package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Marley is an occupied, policed country, and anyone from the island is a devil on sight.
 * <ul>
 *   <li><b>Heat.</b> Just being there draws it, and it never quite goes away while you stay:
 *   seen by soldiers or citizens, flying on the gear (no Marleyan has one), striking soldiers,
 *   killing anyone. Suspected, the patrols around close in on where you were seen; Hunted, squads
 *   are sent after you; Manhunt, officers, brutes and headsmen come too, and keep coming.</li>
 *   <li><b>Life everywhere.</b> Across the whole country, not just its three cities: checkpoints,
 *   patrols, garrisons, Eldian work gangs under guard, markets, villages, refugee camps, Warrior
 *   candidates drilling, brute pits, a headsman at work, bandit and mercenary camps. Each comes
 *   to life as you come near and goes quiet when everyone has left.</li>
 *   <li><b>Events.</b> Now and then, somewhere near: a supply convoy to hit, an Eldian round-up to
 *   break up, a Warrior exhibition to crash. Marked on the map, paid well.</li>
 * </ul>
 */
public final class Occupation {
    private Occupation() {}

    public static final String TAG = "aot_mlife";
    private static MinecraftServer server;

    // ------------------------------------------------------------------ where Marley is

    private static int[] box;

    /** Marley's land: west of its east coast (just past its cities), within its north-south span. */
    public static boolean in(double x, double z) {
        if (box == null) {
            int maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, n = 0;
            for (Net.Area a : AotRpg.PLACES.areas()) {
                if (!a.look().equals("marley")) continue;
                maxX = Math.max(maxX, a.x());
                minZ = Math.min(minZ, a.z());
                maxZ = Math.max(maxZ, a.z());
                n++;
            }
            if (n == 0) return false;
            int cz = (minZ + maxZ) / 2;
            box = new int[] {maxX + 450, cz - 4300, cz + 4300, maxX - 4300};
        }
        return x < box[0] && x > box[3] && z > box[1] && z < box[2];
    }

    private static int level(double x, double z) {
        return Math.max(68, AotRpg.PLACES.levelAt(x, z));
    }

    // ------------------------------------------------------------------ heat

    private static final class Heat {
        float heat;
        int level, sent, lastSent = -1;
        long nextHunt;
        boolean wasIn;
    }

    private static final Map<UUID, Heat> heat = new HashMap<>();
    private static final String[] LEVELS = {"", "SUSPECTED", "HUNTED", "MANHUNT"};

    private static Heat h(ServerPlayerEntity p) {
        return heat.computeIfAbsent(p.getUuid(), k -> new Heat());
    }

    /** Draws heat (seen doing something). */
    public static void raise(ServerPlayerEntity p, float amount) {
        if (!in(p.getX(), p.getZ()) || p.getWorld() != server.getOverworld()) return;
        Heat x = h(p);
        x.heat = Math.min(100, x.heat + amount);
    }

    public static int levelOf(ServerPlayerEntity p) {
        Heat x = heat.get(p.getUuid());
        return x == null ? 0 : x.level;
    }

    private static int levelFor(float v) {
        return v >= 80 ? 3 : v >= 50 ? 2 : v >= 25 ? 1 : 0;
    }

    private static void heatTick(ServerWorld w, ServerPlayerEntity p, long now) {
        Heat x = h(p);
        boolean inside = in(p.getX(), p.getZ()) && !p.isSpectator() && !p.isCreative();
        if (!inside) {
            x.heat = Math.max(0, x.heat - 6);
            if (x.wasIn && x.heat <= 0) Notify.toast(p, Text.literal("Out of Marley").formatted(Formatting.GRAY), Text.literal("Nobody is looking for you here"), 0x8A857A);
            x.wasIn = false;
        } else {
            if (!x.wasIn) {
                Notify.toast(p, Text.literal("Marley").formatted(Formatting.RED), Text.literal("Occupied country. You are a devil on sight here."), 0xE04A3A);
                x.wasIn = true;
                x.heat = Math.max(x.heat, 10);
            }
            float gain = 0.15f;
            // Seen: soldiers first, citizens raise the alarm.
            int seenBy = 0;
            for (var e : w.getOtherEntities(p, p.getBoundingBox().expand(40, 16, 40), e -> e instanceof VillagerEntity v && v.isAlive())) {
                VillagerEntity v = (VillagerEntity) e;
                double d = v.squaredDistanceTo(p);
                if (Troops.marleyan(v) && d < 40 * 40 && v.canSee(p)) seenBy += 2;
                else if (v.getCommandTags().contains(TAG) && d < 12 * 12 && v.canSee(p)) {
                    seenBy++;
                    if (w.random.nextInt(8) == 0) w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.8f);
                }
            }
            gain += Math.min(6, seenBy * 1.2f);
            // On the gear: nobody in Marley flies like that.
            if (!p.isOnGround() && p.getY() - w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getBlockX(), p.getBlockZ()) > 4) gain += 1.2f;
            if (seenBy == 0) gain -= 1.1f;
            // Never quite safe while you're here.
            x.heat = Math.max(10, Math.min(100, x.heat + gain));
        }
        int lv = levelFor(x.heat);
        if (lv != x.level) {
            if (lv > x.level) {
                p.playSoundToPlayer(lv >= 3 ? SoundEvents.EVENT_RAID_HORN.value() : SoundEvents.BLOCK_BELL_USE, SoundCategory.HOSTILE, lv >= 3 ? 0.6f : 1f, 0.8f);
                String sub = lv == 1 ? "They're looking for whoever was seen here" : lv == 2 ? "Squads are being sent after you" : "Everyone is coming. Get out of Marley";
                Notify.toast(p, Text.literal(LEVELS[lv].charAt(0) + LEVELS[lv].substring(1).toLowerCase()).formatted(Formatting.RED, Formatting.BOLD), Text.literal(sub), 0xE04A3A);
                // Suspected: the patrols round about close in on where you were seen.
                for (Spot s : spots) if (s.live && s.at.getSquaredDistance(p.getPos()) < 400 * 400) Troops.lure(s.session(), p.getBlockPos());
            }
            x.level = lv;
            if (lv == 0) x.nextHunt = 0;
        }
        // Hunted: squads sent after you, closer together the hotter you are.
        if (inside && x.level >= 2 && now >= x.nextHunt) {
            x.nextHunt = now + (x.level >= 3 ? 40_000 : 75_000);
            if (huntsFor(p) < (x.level >= 3 ? 3 : 2)) sendHunters(w, p, x.level);
        }
        if (x.sent++ % 2 == 0 || x.lastSent != (int) x.heat) {
            x.lastSent = (int) x.heat;
            if (ServerPlayNetworking.canSend(p, Net.Wanted.ID)) ServerPlayNetworking.send(p, new Net.Wanted(inside || x.heat > 0 ? (int) x.heat : 0, x.level));
        }
    }

    private record Hunt(String session, UUID prey, long until) { }

    private static final List<Hunt> hunts = new ArrayList<>();
    private static int seq;

    private static int huntsFor(ServerPlayerEntity p) {
        int n = 0;
        for (Hunt h : hunts) if (h.prey().equals(p.getUuid())) n++;
        return n;
    }

    private static BlockPos ringSpot(ServerWorld w, Vec3d c, int dMin, int dMax) {
        Random r = w.random;
        for (int t = 0; t < 12; t++) {
            double a = r.nextDouble() * Math.PI * 2, d = dMin + r.nextInt(Math.max(1, dMax - dMin));
            int x = (int) (c.x + Math.cos(a) * d), z = (int) (c.z + Math.sin(a) * d);
            if (!in(x, z) || !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos b = new BlockPos(x, y, z);
            if (y <= w.getSeaLevel() || !w.getFluidState(b.down()).isEmpty()) continue;
            return b;
        }
        return null;
    }

    private static void sendHunters(ServerWorld w, ServerPlayerEntity p, int lv) {
        BlockPos at = ringSpot(w, p.getPos(), 55, 95);
        if (at == null) return;
        Random r = w.random;
        String session = "mhunt:" + (seq++);
        int level = level(at.getX(), at.getZ());
        int made;
        if (lv >= 3 && r.nextInt(3) == 0) {
            made = Troops.hunt(w, at, r.nextBoolean() ? 1 : 2, level + 2, session, at, 600, r, r.nextBoolean() ? Troops.Arch.BRUTE : Troops.Arch.HEADSMAN,
                4 + r.nextInt(2), p.getUuid());
        } else {
            made = Troops.hunt(w, at, (lv >= 3 ? 5 : 3) + r.nextInt(2), level + (lv >= 3 ? 2 : 0), session, at, 600, r, Troops.Arch.MARLEY,
                lv >= 3 ? 4 : 3, p.getUuid());
        }
        if (made > 0) {
            hunts.add(new Hunt(session, p.getUuid(), System.currentTimeMillis() + 240_000));
            p.playSoundToPlayer(SoundEvents.ENTITY_PILLAGER_CELEBRATE, SoundCategory.HOSTILE, 0.7f, 0.7f);
        }
    }

    // ------------------------------------------------------------------ life all over

    private static final int SPOT_STEP = 520;

    /** Kinds of place, with how many of each in a hundred. */
    private enum Kind {
        CHECKPOINT(14), PATROL(12), GARRISON(8), WORK_GANG(10), MARKET(8), VILLAGE(12), REFUGEES(7), TRAINING(6), BRUTE_PIT(5),
        HEADSMAN(4), BANDITS(6), MERCENARIES(8);

        final int weight;

        Kind(int weight) {
            this.weight = weight;
        }
    }

    private static final class Spot {
        final int id;
        final BlockPos at;
        final Kind kind;
        boolean live, dead;
        long quietSince, back;
        final List<UUID> folk = new ArrayList<>();

        Spot(int id, BlockPos at, Kind kind) {
            this.id = id;
            this.at = at;
            this.kind = kind;
        }

        String session() {
            return "mlife:" + id;
        }
    }

    private static final List<Spot> spots = new ArrayList<>();
    private static final Set<UUID> ours = new HashSet<>();

    private static void layOut() {
        spots.clear();
        if (box == null && !in(0, 0) && box == null) return;
        java.util.Random r = new java.util.Random(0x4D41524CL);
        int total = 0;
        for (Kind k : Kind.values()) total += k.weight;
        int id = 0;
        for (int x = box[3] + SPOT_STEP / 2; x < box[0]; x += SPOT_STEP) {
            for (int z = box[1] + SPOT_STEP / 2; z < box[2]; z += SPOT_STEP) {
                int jx = x + r.nextInt(SPOT_STEP / 2) - SPOT_STEP / 4, jz = z + r.nextInt(SPOT_STEP / 2) - SPOT_STEP / 4;
                // Not on top of the cities (they have their own people).
                if (AotRpg.PLACES.nearest(jx, jz, 380, "marley") != null) continue;
                int roll = r.nextInt(total);
                Kind kind = Kind.CHECKPOINT;
                for (Kind k : Kind.values()) {
                    if (roll < k.weight) {
                        kind = k;
                        break;
                    }
                    roll -= k.weight;
                }
                spots.add(new Spot(id++, new BlockPos(jx, 0, jz), kind));
            }
        }
        AotRpg.LOG.info("Marley: {} places of life laid out", spots.size());
    }

    private static int livingSpots() {
        int n = 0;
        for (Spot s : spots) if (s.live) n++;
        return n;
    }

    private static void lifeTick(ServerWorld w, List<ServerPlayerEntity> players, long now) {
        if (spots.isEmpty()) layOut();
        for (Spot s : spots) {
            double near = Double.MAX_VALUE;
            for (ServerPlayerEntity p : players) near = Math.min(near, Math.hypot(p.getX() - s.at.getX(), p.getZ() - s.at.getZ()));
            if (s.live) {
                // Wiped out: quiet for a good while. Nobody about: gone, back when someone comes.
                boolean wiped = Troops.count(s.session()) == 0 && aliveFolk(w, s) == 0;
                if (near > 300) {
                    if (s.quietSince == 0) s.quietSince = now;
                } else s.quietSince = 0;
                if (wiped || s.quietSince > 0 && now - s.quietSince > 45_000) {
                    calm(w, s);
                    if (wiped) s.back = now + 10 * 60_000;
                }
            } else if (near < 190 && now >= s.back && livingSpots() < 28) {
                wake(w, s);
            }
        }
    }

    private static int aliveFolk(ServerWorld w, Spot s) {
        int n = 0;
        for (UUID id : s.folk) if (w.getEntity(id) instanceof VillagerEntity v && v.isAlive()) n++;
        return n;
    }

    private static void calm(ServerWorld w, Spot s) {
        Troops.clear(w, s.session());
        for (UUID id : s.folk) {
            var e = w.getEntity(id);
            if (e != null) e.discard();
            ours.remove(id);
        }
        s.folk.clear();
        s.live = false;
        s.quietSince = 0;
    }

    private static BlockPos ground(ServerWorld w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos b = new BlockPos(x, y, z);
        if (y <= w.getSeaLevel() || !w.getFluidState(b.down()).isEmpty()) return null;
        return b;
    }

    private static void wake(ServerWorld w, Spot s) {
        BlockPos at = ground(w, s.at.getX(), s.at.getZ());
        if (at == null) {
            s.back = System.currentTimeMillis() + 30_000;
            return;
        }
        s.live = true;
        s.quietSince = 0;
        Random r = w.random;
        String ses = s.session();
        int lv = level(at.getX(), at.getZ());
        switch (s.kind) {
            case CHECKPOINT -> Troops.group(w, at, 3, lv, ses, at, 10, r, Troops.Arch.MARLEY, 2);
            case PATROL -> Troops.group(w, at, 4, lv, ses, at, 240, r, Troops.Arch.MARLEY, 2 + r.nextInt(2));
            case GARRISON -> Troops.group(w, at, 6, lv + 1, ses, at, 40, r, Troops.Arch.MARLEY, 3);
            case WORK_GANG -> {
                Troops.group(w, at, 2, lv, ses, at, 18, r, Troops.Arch.MARLEY, 2);
                folk(w, s, at, 5, "Eldian Laborer", 8);
            }
            case MARKET -> {
                folk(w, s, at, 4, "Marleyan Citizen", 9);
                folk(w, s, at, 2, "Marleyan Merchant", 5);
                Troops.group(w, at, 2, lv, ses, at, 20, r, Troops.Arch.MERCENARY, 3);
            }
            case VILLAGE -> folk(w, s, at, 6 + r.nextInt(3), "Marleyan Citizen", 16);
            case REFUGEES -> folk(w, s, at, 5 + r.nextInt(3), "Eldian Refugee", 10);
            case TRAINING -> {
                Troops.group(w, at, 4, lv + 1, ses, at, 14, r, Troops.Arch.MARLEY, 4);
                folk(w, s, at, 3, "Warrior Candidate", 7);
            }
            case BRUTE_PIT -> {
                Troops.group(w, at, 1 + r.nextInt(2), lv + 2, ses, at, 10, r, Troops.Arch.BRUTE, 4);
                folk(w, s, at, 5, "Marleyan Citizen", 9);
            }
            case HEADSMAN -> {
                Troops.group(w, at, 1, lv + 3, ses, at, 10, r, Troops.Arch.HEADSMAN, 5);
                folk(w, s, at, 2, "Eldian Laborer", 4);
            }
            case BANDITS -> Troops.group(w, at, 3 + r.nextInt(2), lv, ses, at, 120, r, Troops.Arch.BANDIT, 3);
            case MERCENARIES -> Troops.group(w, at, 2 + r.nextInt(2), lv + 1, ses, at, 150, r, Troops.Arch.MERCENARY, 3 + r.nextInt(2));
        }
    }

    /** People who live here: standing about, minding their business (and calling out if they see a devil). */
    private static void folk(ServerWorld w, Spot s, BlockPos at, int n, String name, int spread) {
        Random r = w.random;
        for (int i = 0; i < n; i++) {
            BlockPos b = ground(w, at.getX() + r.nextInt(spread * 2 + 1) - spread, at.getZ() + r.nextInt(spread * 2 + 1) - spread);
            if (b == null) continue;
            VillagerEntity v = EntityType.VILLAGER.create(w);
            if (v == null) continue;
            v.refreshPositionAndAngles(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, r.nextFloat() * 360, 0);
            v.initialize(w, w.getLocalDifficulty(b), SpawnReason.EVENT, null);
            v.setVillagerData(v.getVillagerData().withProfession(VillagerProfession.NONE));
            v.setBaby(false);
            v.setAiDisabled(true);
            v.setSilent(true);
            v.addCommandTag(TAG);
            v.addCommandTag("aot_walker");
            v.setCustomName(Text.literal(name));
            v.setCustomNameVisible(false);
            ours.add(v.getUuid());
            if (w.spawnEntity(v)) s.folk.add(v.getUuid());
            else ours.remove(v.getUuid());
        }
    }

    // ------------------------------------------------------------------ events

    private enum EventKind {
        CONVOY("Marleyan Supply Convoy", 150, 0xE0B96A),
        ROUNDUP("Eldian Round-up", 120, 0xE04A3A),
        EXHIBITION("Warrior Exhibition", 260, 0xB06AE0);

        final String title;
        final int marks, color;

        EventKind(String title, int marks, int color) {
            this.title = title;
            this.marks = marks;
            this.color = color;
        }
    }

    private static final class Event {
        EventKind kind;
        BlockPos at;
        String session;
        long until;
        boolean live, done;
        final List<UUID> folk = new ArrayList<>();
    }

    private static Event event;
    private static long nextEvent;

    private static void eventTick(ServerWorld w, List<ServerPlayerEntity> inside, long now) {
        if (event != null) {
            Event e = event;
            boolean near = false;
            for (ServerPlayerEntity p : w.getPlayers()) if (Math.hypot(p.getX() - e.at.getX(), p.getZ() - e.at.getZ()) < 170) near = true;
            if (!e.live && near) {
                BlockPos g = ground(w, e.at.getX(), e.at.getZ());
                if (g != null) {
                    e.at = g;
                    e.live = true;
                    Random r = w.random;
                    int lv = level(g.getX(), g.getZ());
                    switch (e.kind) {
                        case CONVOY -> Troops.group(w, g, 6, lv + 1, e.session, g, 300, r, Troops.Arch.MARLEY, 3);
                        case ROUNDUP -> {
                            Troops.group(w, g, 4, lv, e.session, g, 20, r, Troops.Arch.MARLEY, 3);
                            Spot tmp = new Spot(-1, g, Kind.WORK_GANG);
                            folk(w, tmp, g, 6, "Eldian Laborer", 7);
                            e.folk.addAll(tmp.folk);
                        }
                        case EXHIBITION -> {
                            Troops.group(w, g, 1, lv + 3, e.session, g, 12, r, Troops.Arch.BRUTE, 5);
                            Troops.group(w, g, 1, lv + 3, e.session, g, 12, r, Troops.Arch.HEADSMAN, 5);
                            Spot tmp = new Spot(-1, g, Kind.MARKET);
                            folk(w, tmp, g, 7, "Marleyan Citizen", 10);
                            e.folk.addAll(tmp.folk);
                        }
                    }
                }
            }
            if (e.live && Troops.count(e.session) == 0) {
                // Done: everyone who was in on it is paid.
                for (ServerPlayerEntity p : w.getPlayers()) {
                    if (p.squaredDistanceTo(Vec3d.ofCenter(e.at)) > 120 * 120 || p.isSpectator()) continue;
                    AotRpg.WALLET.addMarks(p, e.kind.marks, e.kind.title);
                    AotRpg.PROGRESSION.award(p, 260L + e.kind.marks, e.kind.title);
                    if (e.kind == EventKind.EXHIBITION && w.random.nextFloat() < 0.25f) AotRpg.COSMETICS.dropFinisher(p, w.random);
                    Notify.toast(p, Text.literal(e.kind.title).formatted(Formatting.GOLD), Text.literal(e.kind == EventKind.ROUNDUP ? "The laborers scatter, free" : "Done. Now get clear"), e.kind.color);
                    raise(p, 20);
                }
                for (UUID id : e.folk) {
                    var f = w.getEntity(id);
                    if (f != null) {
                        w.spawnParticles(net.minecraft.particle.ParticleTypes.CLOUD, f.getX(), f.getY() + 0.8, f.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
                        f.discard();
                    }
                    ours.remove(id);
                }
                event = null;
                nextEvent = now + 6 * 60_000 + w.random.nextInt(3 * 60_000);
            } else if (now > e.until) {
                Troops.clear(w, e.session);
                for (UUID id : e.folk) {
                    var f = w.getEntity(id);
                    if (f != null) f.discard();
                    ours.remove(id);
                }
                event = null;
                nextEvent = now + 4 * 60_000;
            }
            return;
        }
        if (inside.isEmpty() || now < nextEvent) return;
        ServerPlayerEntity p = inside.get(w.random.nextInt(inside.size()));
        Event e = new Event();
        EventKind[] kinds = EventKind.values();
        e.kind = kinds[w.random.nextInt(kinds.length)];
        double a = w.random.nextDouble() * Math.PI * 2, d = 260 + w.random.nextInt(200);
        int x = (int) (p.getX() + Math.cos(a) * d), z = (int) (p.getZ() + Math.sin(a) * d);
        if (!in(x, z)) {
            nextEvent = now + 30_000;
            return;
        }
        e.at = new BlockPos(x, 70, z);
        e.session = "mevent:" + (seq++);
        e.until = now + 12 * 60_000;
        event = e;
        for (ServerPlayerEntity o : inside) {
            Notify.toast(o, Text.literal(e.kind.title).formatted(Formatting.GOLD), Text.literal("Somewhere near: on your map"), e.kind.color);
        }
    }

    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        if (event != null && in(p.getX(), p.getZ())) list.add(new Net.Marker("event", event.kind.title, event.at.getX(), 70, event.at.getZ(), event.kind.color));
    }

    // ------------------------------------------------------------------ wiring

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            box = null;
            spots.clear();
            hunts.clear();
            event = null;
            nextEvent = System.currentTimeMillis() + 3 * 60_000;
        });
        // Left over from before a restart: gone (their places come back to life when someone comes).
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(TAG) && !ours.contains(e.getUuid())) e.discard();
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, src, base, taken, blocked) -> {
            if (src.getAttacker() instanceof ServerPlayerEntity p && Troops.marleyan(e)) raise(p, 3);
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            if (!(src.getAttacker() instanceof ServerPlayerEntity p)) return;
            if (Troops.marleyan(e)) raise(p, 14);
            else if (e.getCommandTags().contains(TAG)) raise(p, 30);
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 != 7) return;
            ServerWorld w = s.getOverworld();
            long now = System.currentTimeMillis();
            List<ServerPlayerEntity> all = new ArrayList<>(), inside = new ArrayList<>();
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (p.isSpectator() || !AotRpg.PROFILES.get(p.getUuid()).created) continue;
                all.add(p);
                if (in(p.getX(), p.getZ())) inside.add(p);
            }
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) if (p.getWorld() == w) heatTick(w, p, now);
            // Hunters: gone when their mark is, when they're wiped out, or after a while.
            for (Hunt hu : new ArrayList<>(hunts)) {
                ServerPlayerEntity mark = s.getPlayerManager().getPlayer(hu.prey());
                if (mark == null || Troops.count(hu.session()) == 0 || now > hu.until || !in(mark.getX(), mark.getZ()) && levelOf(mark) == 0) {
                    Troops.clear(w, hu.session());
                    hunts.remove(hu);
                }
            }
            if (s.getTicks() % 60 == 7 && box != null || box == null && s.getTicks() % 200 == 7) {
                if (in(0, 0) || box != null) {
                    lifeTick(w, all, now);
                    eventTick(w, inside, now);
                }
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> {
            Heat x = heat.get(n.getUuid());
            if (x != null && !alive) x.heat = Math.min(x.heat, 30);
        });
    }
}
