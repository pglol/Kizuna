package net.pglol.kata;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A sparring partner for the gym.
 *
 * Left alone it stands easy at its spot. Hit it and it fights back:
 *  - it chases whoever hit it and keeps to fighting distance
 *  - it reads your wind-ups and tries to get its guard up in time, with a human's reaction: sometimes
 *    late, sometimes guessing wrong, now and then a perfect parry
 *  - it throws jabs, straights and kicks of its own
 * Leave it be for a while (or get far enough away) and it walks back to its spot and settles. It never
 * goes down for good: a finishing hit resets it, and it heals while it rests.
 */
public class Dummy extends PathAwareEntity {
    private static final TrackedData<Boolean> GUARD = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.BOOLEAN);
    /** The move it's throwing (a Fighting.MOVES id, -1 none), when it started (age), and which limb. */
    private static final TrackedData<Integer> MOVE = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> MOVE_AT = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    /** Knocked down (ticks left), staggered (ticks left), and the last block/parry (age, kind). */
    private static final TrackedData<Integer> DOWN = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> STAGGER = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> REACT_AT = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> REACT = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    /** How it's blocking: 0 a plain high guard, 1 meeting it with the right arm, 2 the left arm, 3 a knee checking a kick. */
    private static final TrackedData<Integer> BLOCK = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    /** Counts every time it's knocked about while down (the client puts each one into its ragdoll). */
    private static final TrackedData<Integer> KNOCKS = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);

    /** Reactions shown on the client: 1 blocked from the right, 2 from the left, 3 parried, 4 caught a kick, 5 guard broken. */
    public static final int BLOCK_R = 1, BLOCK_L = 2, PARRY = 3, CATCH = 4, BROKEN = 5;

    private BlockPos home;
    private LivingEntity foe;
    private int calmAt, guardAt = -999, guardUntil, raiseAt = -1, cooldown = 40, contactAt = -1;
    private Fighting.Move swing;
    private int stuck, string;

    public Dummy(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        setPersistent();
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 60)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 24)
            .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.3);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder b) {
        super.initDataTracker(b);
        b.add(GUARD, false);
        b.add(MOVE, -1);
        b.add(MOVE_AT, 0);
        b.add(DOWN, 0);
        b.add(STAGGER, 0);
        b.add(REACT_AT, -999);
        b.add(REACT, 0);
        b.add(KNOCKS, 0);
        b.add(BLOCK, 0);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(5, new LookAtEntityGoal(this, PlayerEntity.class, 10));
    }

    // ---- what the client shows ----

    public boolean guarding() {
        return dataTracker.get(GUARD);
    }

    public int guardAge() {
        return guarding() ? age - guardAt : 999;
    }

    public int move() {
        return dataTracker.get(MOVE);
    }

    public int moveAge() {
        return age - dataTracker.get(MOVE_AT);
    }

    public int down() {
        return dataTracker.get(DOWN);
    }

    public int staggered() {
        return dataTracker.get(STAGGER);
    }

    public int blockKind() {
        return dataTracker.get(BLOCK);
    }

    public int knocks() {
        return dataTracker.get(KNOCKS);
    }

    public int reaction() {
        return dataTracker.get(REACT);
    }

    public int reactionAge() {
        return age - dataTracker.get(REACT_AT);
    }

    public boolean fighting() {
        return foe != null || move() >= 0 || guarding();
    }

    // ---- being hit ----

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.GENERIC_KILL) || source.isOf(DamageTypes.OUT_OF_WORLD)) return super.damage(source, amount);
        if (!getWorld().isClient && source.getAttacker() instanceof LivingEntity l && l != this) {
            provoke(l);
            if (down() > 0) {
                // Hit while it's down: knocked flying again, and it stays down longer.
                Vec3d away = getPos().subtract(l.getPos()).multiply(1, 0, 1);
                away = away.lengthSquared() < 1e-4 ? Vec3d.ZERO : away.normalize();
                setVelocity(getVelocity().add(away.multiply(0.25 + amount * 0.05)).add(0, 0.2, 0));
                velocityModified = true;
                dataTracker.set(DOWN, Math.max(down(), 40));
                dataTracker.set(KNOCKS, dataTracker.get(KNOCKS) + 1);
            }
        }
        // Never knocked out: a hit that would finish it resets it instead.
        if (amount >= getHealth() - 0.5f) setHealth(getMaxHealth());
        return super.damage(source, Math.min(amount, getMaxHealth() - 1));
    }

    private void provoke(LivingEntity by) {
        foe = by;
        calmAt = age + 200; // ten seconds of nothing and it lets it go
    }

    /** A strike is coming (a player's wind-up): try to get the guard up in time, like a person would. */
    void anticipate(LivingEntity by, Fighting.Move m, int contactTicks, boolean rightLimb) {
        provoke(by);
        if (down() > 0 || staggered() > 0 || move() >= 0) return;
        if (random.nextFloat() > 0.65f) return; // didn't read it
        int delay = 2 + random.nextInt(7); // 100-400 ms to react
        raiseAt = age + Math.max(1, delay);
        // Meet it where it's coming: a kick is checked with the knee; a hand strike with the arm on that
        // side (their right comes in on its left).
        boolean lowKick = m.leg() && m.path() != Fighting.Path.FRONT_KICK && m.path() != Fighting.Path.SIDE_KICK && m.path() != Fighting.Path.BACK_KICK && m.path() != Fighting.Path.DROPKICK;
        if (lowKick) dataTracker.set(BLOCK, 3);
        else if (m.leg()) dataTracker.set(BLOCK, 0);
        else {
            // Which of its sides the strike comes in on, from where the striking hand really is.
            double ay = Math.toRadians(by.getYaw());
            Vec3d hand = by.getPos().add(-Math.cos(ay) * (rightLimb ? 0.35 : -0.35), 0, -Math.sin(ay) * (rightLimb ? 0.35 : -0.35));
            double my = Math.toRadians(bodyYaw);
            Vec3d to = hand.subtract(getPos());
            boolean onRight = to.x * -Math.cos(my) + to.z * -Math.sin(my) > 0;
            dataTracker.set(BLOCK, onRight ? 1 : 2);
        }
    }

    /** Where its knocked-down body came to rest (seen on a player's screen): it gets up there. */
    void settleAt(Vec3d at, boolean resting) {
        if (down() <= 0 || getPos().squaredDistanceTo(at) > 12 * 12) return;
        // Stand where the body lies (lifted out of the floor it's lying on, if need be).
        for (double up = 0; up <= 1.5; up += 0.1) {
            Vec3d spot = at.add(0, up, 0);
            if (getWorld().isSpaceEmpty(this, getBoundingBox().offset(spot.subtract(getPos())))) {
                refreshPositionAndAngles(spot.x, spot.y, spot.z, getYaw(), getPitch());
                setVelocity(Vec3d.ZERO);
                break;
            }
        }
        getNavigation().stop();
        // While it's flying the dummy goes with its body; at rest, getting up takes a moment, then it's back on its feet.
        if (resting) dataTracker.set(DOWN, Math.min(down(), 24));
    }

    void blocked(int side) {
        react(side > 0 ? BLOCK_R : BLOCK_L);
        guardUntil = Math.max(guardUntil, age + 10);
    }

    void parried(boolean kick) {
        react(kick ? CATCH : PARRY);
    }

    void guardBroken() {
        setGuard(false);
        react(BROKEN);
        stagger(12);
    }

    void stagger(int ticks) {
        dataTracker.set(STAGGER, ticks);
        cancelSwing();
        setGuard(false);
        raiseAt = -1;
    }

    void knockdown(Vec3d v) {
        setVelocity(v);
        velocityModified = true;
        dataTracker.set(DOWN, 200); // down until its body is seen to come to rest (then it gets up), ten seconds at most
        cancelSwing();
        setGuard(false);
        raiseAt = -1;
        getNavigation().stop();
    }

    private void react(int kind) {
        dataTracker.set(REACT, kind);
        dataTracker.set(REACT_AT, age);
    }

    private void setGuard(boolean on) {
        if (on && !guarding()) guardAt = age;
        dataTracker.set(GUARD, on);
    }

    private void cancelSwing() {
        swing = null;
        contactAt = -1;
        dataTracker.set(MOVE, -1);
    }

    // ---- thinking ----

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        if (home == null) home = getBlockPos();
        if (down() > 0) dataTracker.set(DOWN, down() - 1);
        if (staggered() > 0) dataTracker.set(STAGGER, staggered() - 1);
        boolean busy = down() > 0 || staggered() > 0;

        // The guard: up when it decided to raise it, down again a moment after nothing more comes.
        if (raiseAt >= 0 && age >= raiseAt && !busy && move() < 0) {
            raiseAt = -1;
            setGuard(true);
            guardUntil = age + 10 + random.nextInt(6);
        }
        if (guarding() && (age > guardUntil || busy)) setGuard(false);

        // Its own strike: contact, then done.
        if (swing != null) {
            if (age == contactAt && foe != null && !busy) hit(foe, swing);
            if (moveAge() >= Math.round(swing.length() * 20)) cancelSwing();
        }

        // Calm: let it go after a while, or if they're gone or far off.
        if (foe != null && (!foe.isAlive() || foe.isSpectator() || age > calmAt || foe.squaredDistanceTo(this) > 20 * 20
            || (foe instanceof PlayerEntity pl && pl.isCreative() && age > calmAt - 100))) foe = null;

        if (busy) return;
        if (foe != null) fight(foe);
        else {
            // Back to its spot, then stands easy and rests.
            if (home != null && home.getSquaredDistance(getPos()) > 2.5) {
                if (age % 20 == 0) getNavigation().startMovingTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.8);
            } else getNavigation().stop();
            if (age % 20 == 0 && hurtTime == 0 && getHealth() < getMaxHealth()) heal(4);
        }
    }

    private void fight(LivingEntity foe) {
        getLookControl().lookAt(foe, 40, 40);
        double d = Math.sqrt(squaredDistanceTo(foe));
        // Keep to fighting distance: close in, but not chest to chest.
        if (swing != null) {
            // Throwing something: feet planted.
            getNavigation().stop();
            stuck = 0;
        } else if (d > 2.6) {
            if (age % 5 == 0) getNavigation().startMovingTo(foe, d > 5 ? 1.15 : 0.9);
            // No path (they're up on something, or round an edge): head straight for them, and hop over
            // what's in the way once it's clearly stuck.
            if (getNavigation().isIdle() && d > 3.2) {
                getMoveControl().moveTo(foe.getX(), foe.getY(), foe.getZ(), d > 5 ? 1.15 : 0.9);
                stuck = horizontalCollision ? stuck + 1 : 0;
                if (stuck > 8 && isOnGround()) {
                    getJumpControl().setActive();
                    stuck = 0;
                }
            }
        } else {
            getNavigation().stop();
            if (d < 1.3) {
                Vec3d away = getPos().subtract(foe.getPos()).multiply(1, 0, 1).normalize().multiply(0.06);
                addVelocity(away.x, 0, away.z);
            }
        }
        // Face them square.
        double dx = foe.getX() - getX(), dz = foe.getZ() - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setYaw(MathHelper.stepUnwrappedAngleTowards(getYaw(), yaw, 15));
        bodyYaw = MathHelper.stepUnwrappedAngleTowards(bodyYaw, getYaw(), 15);
        headYaw = getYaw();

        // Its turn: strings of one to three strikes, often; out of a block it counters (dropping the
        // guard to hit back); just out of reach it steps in to strike.
        if (swing == null && d < 3.4 && --cooldown <= 0) {
            boolean counter = guarding() && age - guardAt > 6;
            if (!guarding() || counter) {
                if (counter) setGuard(false);
                if (d > 2.5) getNavigation().startMovingTo(foe, 1.1); // a step in to reach
                Fighting.Style s = Fighting.style("taijutsu");
                String[] picks = {"jab", "jab", "straight", "straight", "one_two", "lead_hook", "rear_hook", "front_kick", "roundhouse", "low_kick", "uppercut"};
                Fighting.Move m = s.move(s.index(picks[random.nextInt(picks.length)]));
                if (m != null) {
                    swing = m;
                    dataTracker.set(MOVE, Fighting.id(m));
                    dataTracker.set(MOVE_AT, age);
                    contactAt = age + Math.max(1, Math.round(m.contact() * 20));
                    if (string <= 0) string = 1 + random.nextInt(3);
                    string--;
                    // The next strike of the string right after this one; a breather after the last.
                    cooldown = Math.round(m.length() * 20) + (string > 0 ? 1 : 12 + random.nextInt(18));
                }
            }
        }
    }

    private void hit(LivingEntity foe, Fighting.Move m) {
        double reach = m.leg() ? 3.0 : 2.7;
        if (squaredDistanceTo(foe) > reach * reach) return;
        Vec3d look = Vec3d.fromPolar(0, getYaw());
        Fighting.land(this, foe, m, 1, Fighting.Part.TORSO, look, getDamageSources().mobAttack(this));
    }

    @Override
    public boolean canImmediatelyDespawn(double distance) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (home != null) nbt.putLong("Home", home.asLong());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Home")) home = BlockPos.fromLong(nbt.getLong("Home"));
    }
}
