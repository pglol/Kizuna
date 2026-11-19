package com.pglol.aotrpg;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * The sound of a blade landing: a cut, a thump and a ring of steel, on every melee blow that
 * connects with a person, a soldier or a titan (heavier for big blows). Blocked, parried or
 * party blows never land, so they never make it.
 */
public final class HitSounds {
    private HitSounds() { }

    public static SoundEvent HIT, HEAVY, SHOT, IMPACT, RICOCHET, WHOOSH, CLASH;

    private static SoundEvent reg(String name) {
        Identifier id = Identifier.of("aot_rpg", name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** A gunshot from here, and the round arriving there (a crack in the dirt, now and then a whine off stone). */
    public static void shot(net.minecraft.server.world.ServerWorld w, net.minecraft.util.math.Vec3d from, net.minecraft.util.math.Vec3d to, SoundCategory cat) {
        float p = 0.94f + w.random.nextFloat() * 0.12f;
        w.playSound(null, from.x, from.y, from.z, SHOT, cat, 4f, p);
        if (to != null && from.squaredDistanceTo(to) > 4) {
            w.playSound(null, to.x, to.y, to.z, IMPACT, cat, 1f, 0.9f + w.random.nextFloat() * 0.2f);
            if (w.random.nextInt(4) == 0) w.playSound(null, to.x, to.y, to.z, RICOCHET, cat, 0.8f, 0.85f + w.random.nextFloat() * 0.3f);
        }
    }

    public static void register() {
        HIT = Registry.register(Registries.SOUND_EVENT, Identifier.of("aot_rpg", "hit"), SoundEvent.of(Identifier.of("aot_rpg", "hit")));
        HEAVY = Registry.register(Registries.SOUND_EVENT, Identifier.of("aot_rpg", "hit_heavy"), SoundEvent.of(Identifier.of("aot_rpg", "hit_heavy")));
        SHOT = reg("apg_shot");
        IMPACT = reg("bullet_impact");
        RICOCHET = reg("ricochet");
        WHOOSH = reg("whoosh");
        CLASH = reg("steel_clash");
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (blocked || taken <= 0 || !Combat.melee(source) || source.getAttacker() == null || source.getAttacker() == victim) return;
            boolean person = victim instanceof ServerPlayerEntity || Troops.is(victim) || AotRpg.isTitan(victim)
                || victim instanceof net.minecraft.entity.passive.VillagerEntity || victim instanceof net.minecraft.entity.mob.HostileEntity;
            if (!person) return;
            if (source.getAttacker() instanceof ServerPlayerEntity a && victim instanceof ServerPlayerEntity v && AotRpg.PARTIES.same(a.getUuid(), v.getUuid())) return;
            boolean heavy = taken >= 9 || AotRpg.isTitan(victim) && taken >= 6;
            float pitch = 0.9f + victim.getRandom().nextFloat() * 0.2f;
            victim.getWorld().playSound(null, victim.getX(), victim.getY() + victim.getHeight() * 0.6, victim.getZ(),
                heavy ? HEAVY : HIT, SoundCategory.PLAYERS, heavy ? 1.1f : 0.9f, AotRpg.isTitan(victim) ? pitch * 0.8f : pitch);
        });
    }
}
