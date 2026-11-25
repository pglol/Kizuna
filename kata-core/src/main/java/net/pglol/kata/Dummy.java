package net.pglol.kata;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;

/**
 * A training dummy for the gym: stands its ground, turns to face whoever's nearest, takes every hit
 * (and gets knocked about a little), and never goes down: its health tops back up.
 */
public class Dummy extends PathAwareEntity {
    public Dummy(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 60)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0)
            .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(1, new LookAtEntityGoal(this, PlayerEntity.class, 10));
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD)) return super.damage(source, amount);
        // Never knocked out: a hit that would finish it resets it instead.
        if (amount >= getHealth() - 0.5f) setHealth(getMaxHealth());
        return super.damage(source, Math.min(amount, getMaxHealth() - 1));
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && age % 20 == 0 && hurtTime == 0 && getHealth() < getMaxHealth()) heal(4);
    }

    @Override
    public boolean canImmediatelyDespawn(double distance) {
        return false;
    }
}
