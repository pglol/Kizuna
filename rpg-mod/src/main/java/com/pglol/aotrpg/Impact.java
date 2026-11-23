package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sending someone flying. A big enough blow (an overpowering clash, a counter off a perfect parry,
 * a guard turned, a troop's shove) launches them; if they hit a wall on the way they slam into it
 * and hang there pinned, limp, for a second and a half: open to whatever comes next.
 */
public final class Impact {
    private Impact() {}

    /** In the air from a blow; power: thrown hard enough to break the wall they hit. */
    private record Flight(LivingEntity e, Vec3d dir, long at, long until, boolean power) { }

    /** dir: into the wall they hit (null when driven into the ground). */
    private record Pin(LivingEntity e, Vec3d at, long until, Vec3d dir) { }

    /** Smashed on through: travelling, how fast, how many more walls it can break, since when. */
    private static final class Rag {
        final LivingEntity e;
        final Vec3d dir;
        double speed, vy;
        int walls;
        final long at;
        final net.minecraft.entity.Entity by;

        Rag(LivingEntity e, Vec3d dir, double speed, int walls, long at, net.minecraft.entity.Entity by) {
            this.e = e;
            this.dir = dir;
            this.speed = speed;
            this.walls = walls;
            this.at = at;
            this.by = by;
        }
    }

    private static final Map<UUID, Rag> rags = new HashMap<>();

    private static final Map<UUID, Flight> flying = new HashMap<>();
    private static final Map<UUID, Pin> pinned = new HashMap<>();
    private static final Map<UUID, Long> knocked = new HashMap<>();
    public static final int PIN_TICKS = 30;

    /** Tumbling through the air after being smashed on through a wall. */
    public static boolean ragdolled(net.minecraft.entity.Entity e) {
        return rags.containsKey(e.getUuid());
    }

    public static void register() {
        // Stuck in a wall and hit again: the blow carries them on, through it and whatever's behind.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, src, base, taken, blocked) -> {
            if (blocked || taken <= 0 || !Combat.melee(src)) return;
            net.minecraft.entity.Entity by = src.getAttacker();
            if (by == null || by == e || AotRpg.isTitan(e)) return;
            Pin p = pinned.get(e.getUuid());
            if (p == null || p.dir() == null || !(e.getWorld() instanceof ServerWorld w)) return;
            smash(w, e, by, p.dir(), taken);
        });
        // Pinned to a wall: no swinging back until you peel off it.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            !world.isClient && pinned.containsKey(player.getUuid()) ? ActionResult.FAIL : ActionResult.PASS);
        // A hard cut sends them flying: the harder the hit, the further (once a second at most each).
        ServerLivingEntityEvents.AFTER_DAMAGE.register((e, src, base, taken, blocked) -> {
            // Only a really heavy cut sends someone flying; ordinary blows just rock them.
            if (blocked || taken < Math.max(14, e.getMaxHealth() * 0.35f) || !(src.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity att) || att == e) return;
            if (AotRpg.isTitan(e) || !Combat.melee(src) || pinned(e) || thrown(e) || Finishers.inScene(e) || Throws.holding(e)) return;
            long now = e.getWorld().getTime();
            Long last = knocked.get(e.getUuid());
            if (last != null && now - last < 24) return;
            knocked.put(e.getUuid(), now);
            if (knocked.size() > 512) knocked.values().removeIf(t -> now - t > 200);
            launch(e, att, 0.7 + Math.min(0.6, (taken / Math.max(1, e.getMaxHealth()) - 0.35) * 1.5));
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((e, src) -> {
            flying.remove(e.getUuid());
            rags.remove(e.getUuid());
            pinned.remove(e.getUuid());
        });
    }

    /** In the air from a throw (their own legs don't move them until they land). */
    public static boolean thrown(net.minecraft.entity.Entity e) {
        return flying.containsKey(e.getUuid()) || rags.containsKey(e.getUuid());
    }

    public static boolean pinned(LivingEntity e) {
        return pinned.containsKey(e.getUuid());
    }

    /** Throws someone (away from `from`) with this much force; watched for a wall for the next ~0.6s. */
    public static void launch(LivingEntity e, net.minecraft.entity.Entity from, double strength) {
        if (e == null || from == null || pinned(e) || ragdolled(e)) return;
        Vec3d d = e.getPos().subtract(from.getPos()).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) d = from.getRotationVec(1f).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) return;
        d = d.normalize();
        // Off their feet: a real arc, so the throw carries (on the ground friction eats it in a step).
        Vec3d v = e.getVelocity();
        e.setVelocity(v.x * 0.3 + d.x * strength, Math.max(v.y, 0) + 0.34 + strength * 0.13, v.z * 0.3 + d.z * strength);
        e.velocityModified = true;
        e.setOnGround(false);
        long now = e.getWorld().getTime();
        if (strength >= 0.7) flying.put(e.getUuid(), new Flight(e, d, now, now + 40, strength >= 1.5));
        if (strength >= 1.4 && e.getWorld() instanceof ServerWorld tw) tumble(tw, e, d, 12 + (int) (strength * 4));
        // A big one cuts to an impact frame for the two of them.
        if (strength >= 1.1 && e.getWorld() instanceof ServerWorld w) {
            Vec3d at = e.getPos().add(from.getPos()).multiply(0.5).add(0, 1.2, 0);
            frame(w, at, (float) Math.min(1.2, strength / 1.5), e, from);
            AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 6f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFF4D8, 4f), at.x, at.y, at.z, 0, 0, 0);
            AotParticles.burst(w, AotParticles.SPARK, 0xFFE8A8, 0.6f, at.x, at.y, at.z, 14, 0.3, 0.6);
            AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 0.8f, e.getX(), e.getY() + 1, e.getZ(), 10, 0.3, strength * 0.8);
        }
    }

    /** An impact frame: for whoever was in it, and anyone standing right there. */
    public static void frame(ServerWorld w, Vec3d at, float strength, net.minecraft.entity.Entity... involved) {
        Net.ImpactFx fx = new Net.ImpactFx(at.x, at.y, at.z, strength);
        java.util.Set<ServerPlayerEntity> to = new java.util.HashSet<>(PlayerLookup.around(w, at, 7));
        for (var e : involved) if (e instanceof ServerPlayerEntity sp) to.add(sp);
        for (ServerPlayerEntity o : to) if (ServerPlayNetworking.canSend(o, Net.ImpactFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    /** Everyone near sees the body go limp and tumble (ticks: for how long at most). */
    private static void tumble(ServerWorld w, LivingEntity e, Vec3d dir, int ticks) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        Net.RushFx fx = new Net.RushFx(e.getId(), Adrenaline.FX_RAGDOLL, yaw, ticks);
        for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 96)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    /** How hard this one hits, as walls it can put someone through (1-4). */
    private static int walls(net.minecraft.entity.Entity by, float taken) {
        double k = 1 + taken / 8.0;
        if (by instanceof LivingEntity le) {
            var atk = le.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (atk != null) k += atk.getValue() / 14.0;
            k += Math.max(0, le.getScale() - 1) * 2;
        }
        if (by instanceof ServerPlayerEntity sp) {
            if (Builds.of(AotRpg.PROFILES.get(sp.getUuid())) == Builds.HEAVY) k += 1;
            if (Adrenaline.titanSurge(sp)) k += 1;
        }
        return (int) Math.max(1, Math.min(4, Math.floor(k)));
    }

    /**
     * Hit while stuck in a wall: through it. The body goes limp and is driven on the way it was
     * going, bursting through the wall and the next ones behind it, as many as the blow has in it,
     * until it runs out of force or meets something it can't break (and sticks in that instead).
     */
    public static void smash(ServerWorld w, LivingEntity e, net.minecraft.entity.Entity by, Vec3d dir, float taken) {
        pinned.remove(e.getUuid());
        flying.remove(e.getUuid());
        int n = walls(by, taken);
        Vec3d d = dir.multiply(1, 0, 1).normalize();
        Rag r = new Rag(e, d, 1.35 + 0.18 * n, n, w.getTime(), by);
        rags.put(e.getUuid(), r);
        e.removeStatusEffect(StatusEffects.SLOWNESS);
        tumble(w, e, d, 16 + n * 6);
        Vec3d at = e.getPos().add(0, e.getHeight() * 0.55, 0);
        frame(w, at, 1.3f, e, by);
        AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFFFFF, 7f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFF0D0, 6f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.burst(w, AotParticles.STREAK, 0xFFFFFF, 0.9f, at.x, at.y, at.z, 16, 0.2, 1.2);
        w.playSound(null, e.getBlockPos(), HitSounds.HEAVY, SoundCategory.PLAYERS, 1.4f, 0.8f);
        w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 0.9f);
        for (ServerPlayerEntity o : PlayerLookup.around(w, at, 28)) {
            if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(o == e || o == by ? 0.8f : 0.35f, 10));
        }
    }

    /** The first solid block in the body's way over the next step, or null. */
    private static BlockPos blocking(ServerWorld w, LivingEntity e, Vec3d from, Vec3d to) {
        double len = from.distanceTo(to) + e.getWidth() * 0.5 + 0.3;
        Vec3d d = to.subtract(from).normalize();
        for (double s = 0.2; s <= len; s += 0.35) {
            for (double h = 0.15; h < e.getHeight(); h += 0.6) {
                BlockPos b = BlockPos.ofFloored(from.add(d.multiply(s)).add(0, h, 0));
                BlockState st = w.getBlockState(b);
                if (!st.isAir() && !st.getCollisionShape(w, b).isEmpty()) return b;
            }
        }
        return null;
    }

    private static void ragTick(ServerWorld w, Rag r) {
        LivingEntity e = r.e;
        Vec3d pos = e.getPos();
        r.vy = Math.max(-0.6, r.vy - 0.035);
        Vec3d step = r.dir.multiply(r.speed).add(0, r.vy, 0);
        Vec3d next = pos.add(step);
        // The floor: skim along it rather than through it.
        BlockPos under = BlockPos.ofFloored(next.x, next.y - 0.05, next.z);
        if (r.vy < 0 && !w.getBlockState(under).getCollisionShape(w, under).isEmpty()) {
            next = new Vec3d(next.x, under.getY() + 1, next.z);
            r.vy = 0;
            r.speed *= 0.86;
        }
        BlockPos wall = blocking(w, e, pos, next);
        if (wall != null) {
            BlockState ws = w.getBlockState(wall);
            boolean breaks = r.walls > 0 && ws.getHardness(w, wall) >= 0 && ws.getHardness(w, wall) <= 6;
            if (breaks) {
                // Through it: a hole the size of a body, and on.
                r.walls--;
                BlockPos mid = BlockPos.ofFloored(wall.getX() + 0.5, pos.y + e.getHeight() * 0.5, wall.getZ() + 0.5);
                crater(w, mid, 1);
                Vec3d at = Vec3d.ofCenter(mid);
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ws), at.x, at.y, at.z, 60, 0.6, 0.8, 0.6, 0.35);
                AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFFFFF, 5f), at.x, at.y, at.z, 0, 0, 0);
                AotParticles.burst(w, AotParticles.SHARD, 0x9A9080, 0.6f, at.x, at.y, at.z, 22, 0.4, 0.7);
                w.playSound(null, mid, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 1.0f, 1.1f);
                w.playSound(null, mid, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.0f, 0.7f);
                e.timeUntilRegen = 0;
                e.damage(w.getDamageSources().flyIntoWall(), 2.5f);
                r.speed *= 0.9;
                frame(w, at, 0.8f, e, r.by);
                for (ServerPlayerEntity o : PlayerLookup.around(w, at, 24)) {
                    if (ServerPlayNetworking.canSend(o, Net.Quake.ID)) ServerPlayNetworking.send(o, new Net.Quake(0.3f, 6));
                }
                // Cleared now? Carry on this tick; if not, next tick tries again.
                if (blocking(w, e, pos, next) != null) return;
            } else {
                // Out of force, or something that won't give: stuck in this one.
                rags.remove(e.getUuid());
                if (e instanceof ServerPlayerEntity sp) sp.setVelocity(Vec3d.ZERO);
                slam(w, e, ws, r.dir);
                return;
            }
        }
        if (e instanceof ServerPlayerEntity sp) {
            sp.setVelocity(step);
            sp.velocityModified = true;
        } else {
            e.setVelocity(step);
            e.refreshPositionAndAngles(next.x, next.y, next.z, e.getYaw(), e.getPitch());
        }
        e.fallDistance = 0;
        if (w.getTime() % 2 == 0) AotParticles.burst(w, AotParticles.STREAK, 0xEAF4FF, 0.5f, e.getX(), e.getY() + 1, e.getZ(), 3, 0.3, 0.3);
        r.speed *= 0.955;
        // Spent: down it comes, the ordinary way.
        if (r.speed < 0.45 || w.getTime() - r.at > 60) {
            rags.remove(e.getUuid());
            long now = w.getTime();
            flying.put(e.getUuid(), new Flight(e, r.dir, now, now + 30, false));
            e.setVelocity(r.dir.multiply(r.speed).add(0, r.vy, 0));
            e.velocityModified = true;
        }
    }

    public static void tick(MinecraftServer server) {
        if (!rags.isEmpty()) {
            for (Rag r : new ArrayList<>(rags.values())) {
                if (r.e.isRemoved() || !r.e.isAlive() || !(r.e.getWorld() instanceof ServerWorld w)) {
                    rags.remove(r.e.getUuid());
                    continue;
                }
                ragTick(w, r);
            }
        }
        if (!flying.isEmpty()) {
            for (var en : new ArrayList<>(flying.entrySet())) {
                Flight f = en.getValue();
                LivingEntity e = f.e();
                long t = e.getWorld().getTime();
                // Down again (after the first moment of the throw), or long enough: landed.
                if (e.isRemoved() || !e.isAlive() || t > f.until() || t - f.at() > 4 && e.isOnGround()) {
                    flying.remove(en.getKey());
                    continue;
                }
                e.fallDistance = Math.min(e.fallDistance, 3);
                // Into a wall in the direction they were thrown.
                if (!e.horizontalCollision || !(e.getWorld() instanceof ServerWorld w)) continue;
                BlockPos wall = BlockPos.ofFloored(e.getPos().add(f.dir().multiply(e.getWidth() * 0.5 + 0.4)).add(0, 1, 0));
                BlockState ws = w.getBlockState(wall);
                if (ws.isAir() || !ws.isSolidBlock(w, wall)) continue;
                flying.remove(en.getKey());
                if (f.power()) crater(w, wall, 1);
                slam(w, e, ws, f.dir());
            }
        }
        if (pinned.isEmpty()) return;
        for (var en : new ArrayList<>(pinned.entrySet())) {
            Pin p = en.getValue();
            LivingEntity e = p.e();
            if (e.isRemoved() || !e.isAlive() || e.getWorld().getTime() > p.until()) {
                pinned.remove(en.getKey());
                continue;
            }
            // Held where they hit.
            if (e instanceof ServerPlayerEntity sp) {
                if (sp.squaredDistanceTo(p.at()) > 0.04) sp.requestTeleport(p.at().x, p.at().y, p.at().z);
            } else {
                e.setVelocity(Vec3d.ZERO);
                e.refreshPositionAndAngles(p.at().x, p.at().y, p.at().z, e.getYaw(), e.getPitch());
            }
            e.fallDistance = 0;
        }
    }

    /**
     * Blocks knocked out where someone was thrown into them (a ball around the spot). Not for
     * good: they grow back half a minute later. Nothing hard, nothing with contents, and never
     * in a home, the lobby or the arenas.
     */
    public static void crater(ServerWorld w, BlockPos at, int r) {
        if (w.getRegistryKey() == Homes.WORLD || w.getRegistryKey() == Arenas.WORLD || w.getRegistryKey() == Extraction.lobbyWorld()) return;
        java.util.List<Object[]> gone = new java.util.ArrayList<>();
        for (BlockPos b : BlockPos.iterate(at.add(-r, -r, -r), at.add(r, r, r))) {
            if (b.getSquaredDistance(at) > r * r + 0.5) continue;
            BlockState st = w.getBlockState(b);
            if (st.isAir() || st.getHardness(w, b) < 0 || st.getHardness(w, b) > 6 || w.getBlockEntity(b) != null || !st.getFluidState().isEmpty()) continue;
            BlockPos keep = b.toImmutable();
            gone.add(new Object[] {keep, st});
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, st), keep.getX() + 0.5, keep.getY() + 0.5, keep.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.2);
            w.setBlockState(keep, net.minecraft.block.Blocks.AIR.getDefaultState(), 2);
        }
        if (gone.isEmpty()) return;
        w.playSound(null, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.BLOCKS, 0.7f, 1.2f);
        w.playSound(null, at, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1.2f, 0.6f);
        AotRpg.SCHEDULER.later(600, () -> {
            for (Object[] g : gone) {
                BlockPos b = (BlockPos) g[0];
                if (w.getBlockState(b).isAir() && w.getEntitiesByClass(LivingEntity.class, new net.minecraft.util.math.Box(b), x -> true).isEmpty())
                    w.setBlockState(b, (BlockState) g[1], 3);
            }
        });
    }

    /** Slammed face first into the ground: flat on it, limp, for a moment. */
    public static void faceplant(ServerWorld w, LivingEntity e, int ticks) {
        pin(w, e, ticks, Adrenaline.FX_FACEPLANT);
    }

    /** Driven into the ground from above: held there, limp, for a moment. */
    public static void pinDown(ServerWorld w, LivingEntity e, int ticks) {
        pin(w, e, ticks, Adrenaline.FX_PINNED);
    }

    private static void pin(ServerWorld w, LivingEntity e, int ticks, int kind) {
        flying.remove(e.getUuid());
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        e.fallDistance = 0;
        pinned.put(e.getUuid(), new Pin(e, e.getPos(), w.getTime() + ticks, null));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 6, false, false, false));
        if (Troops.is(e)) Troops.stagger(e, ticks);
        Net.RushFx fx = new Net.RushFx(e.getId(), kind, e.getYaw(), ticks);
        for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 64)) if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
    }

    private static void slam(ServerWorld w, LivingEntity e, BlockState wall, Vec3d dir) {
        e.setVelocity(Vec3d.ZERO);
        e.velocityModified = true;
        pinned.put(e.getUuid(), new Pin(e, e.getPos(), w.getTime() + PIN_TICKS, dir));
        e.damage(w.getDamageSources().flyIntoWall(), 3);
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, PIN_TICKS, 6, false, false, false));
        e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, PIN_TICKS, 2, false, false, false));
        if (Troops.is(e)) Troops.stagger(e, PIN_TICKS);
        Vec3d at = e.getPos().add(dir.multiply(e.getWidth() * 0.5)).add(0, e.getHeight() * 0.55, 0);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wall), at.x, at.y, at.z, 40, 0.3, 0.6, 0.3, 0.25);
        AotParticles.one(w, AotParticles.fx(AotParticles.BURST, 0xFFFFFF, 5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.one(w, AotParticles.fx(AotParticles.FLASH, 0xFFF0D0, 5f), at.x, at.y, at.z, 0, 0, 0);
        AotParticles.burst(w, AotParticles.SHARD, 0x9A9080, 0.55f, at.x, at.y, at.z, 16, 0.3, 0.45);
        frame(w, at, 1f, e);
        w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.5f, 1.4f);
        w.playSound(null, e.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.6f);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        Net.RushFx fx = new Net.RushFx(e.getId(), Adrenaline.FX_PINNED, yaw, PIN_TICKS);
        for (ServerPlayerEntity o : PlayerLookup.around(w, e.getPos(), 64)) {
            if (ServerPlayNetworking.canSend(o, Net.RushFx.ID)) ServerPlayNetworking.send(o, fx);
            if (ServerPlayNetworking.canSend(o, Net.Quake.ID) && o.squaredDistanceTo(e) < 20 * 20) ServerPlayNetworking.send(o, new Net.Quake(o == e ? 0.6f : 0.25f, 8));
        }
    }
}
