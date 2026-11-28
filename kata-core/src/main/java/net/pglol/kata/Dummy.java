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
 * A sparring partner, and the template for Kata's NPC fighters.
 *
 * Left alone it stands easy at its spot. Hit it and it fights back, deciding each tick what to do
 * (see decide): close in, keep its spacing, strike (strings of one to three, countering out of a
 * block), guard, or take it to the air (a jump at them, a second jump with a flip near the top,
 * air strikes in reach). A strike coming at it (anticipate) it reads like a person: guards it on the
 * right side, dodges it (back, or aside from a straight line, with the same i-frames a player gets),
 * or misreads it. Its striking limb glows as it comes, like a player's. Leave it be and it walks back
 * to its spot and settles; it never goes down for good, and heals while it rests.
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
    /** Who it's fighting (entity id, -1 for no one): every screen aims its strikes at them. */
    private static final TrackedData<Integer> FOE = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);
    /** Counts every time it's knocked about while down (the client puts each one into its ragdoll). */
    private static final TrackedData<Integer> KNOCKS = DataTracker.registerData(Dummy.class, TrackedDataHandlerRegistry.INTEGER);

    /** Reactions shown on the client: 1 blocked from the right, 2 from the left, 3 parried, 4 caught a kick, 5 guard broken. */
    public static final int BLOCK_R = 1, BLOCK_L = 2, PARRY = 3, CATCH = 4, BROKEN = 5;

    private BlockPos home;
    private LivingEntity foe;

    /** Who it's fighting, on either side (null for no one). */
    public LivingEntity foe() {
        if (!getWorld().isClient) return foe;
        int id = dataTracker.get(FOE);
        return id >= 0 && getWorld().getEntityById(id) instanceof LivingEntity l ? l : null;
    }
    private int calmAt, guardAt = -999, guardUntil, raiseAt = -1, cooldown = 40, contactAt = -1;
    private Fighting.Move swing;
    private int stuck, string, airCooldown, dodgeAt = -1, dodgeDir;
    /** The lunge behind its strike: how far it still carries it in, over how many ticks. */
    private double lungeLeft;
    private int lungeTicks;
    private boolean secondJump;

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
        b.add(FOE, -1);
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
        dataTracker.set(FOE, by.getId());
        calmAt = age + 200; // ten seconds of nothing and it lets it go
    }

    /** A strike is coming (a player's wind-up): try to get the guard up in time, like a person would. */
    void anticipate(LivingEntity by, Fighting.Move m, int contactTicks, boolean rightLimb) {
        provoke(by);
        if (down() > 0 || staggered() > 0 || move() >= 0) return;
        evadeOrGuard(by, m, rightLimb);
    }

    /** Meet it where it's coming: a kick checked with the knee; a hand strike with the arm on that side (their right comes in on its left). */
    private void blockSide(LivingEntity by, Fighting.Move m, boolean rightLimb) {
        boolean lowKick = m.leg() && m.path() != Fighting.Path.FRONT_KICK && m.path() != Fighting.Path.SIDE_KICK && m.path() != Fighting.Path.BACK_KICK && m.path() != Fighting.Path.DROPKICK;
        if (lowKick) dataTracker.set(BLOCK, 3);
        else if (m.leg()) dataTracker.set(BLOCK, 0);
        else {
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

    /** Hit: whatever it was throwing is gone, and it can't throw again for a moment (it can still guard). */
    /** Its strike just landed: held where it met for the hit-stop (the swing and the string wait with it). */
    void hitStop(int ticks) {
        if (swing == null) return;
        dataTracker.set(MOVE_AT, dataTracker.get(MOVE_AT) + ticks);
        cooldown += ticks;
        lungeLeft = 0;
        setVelocity(0, getVelocity().y, 0);
        velocityModified = true;
    }

    void flinch(int ticks) {
        cancelSwing();
        cooldown = Math.max(cooldown, ticks);
        string = 0;
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
        if (dodgeAt >= 0 && age >= dodgeAt) {
            dodgeAt = -1;
            if (!busy && swing == null) dodgeNow();
        }
        if (isOnGround() && airCooldown > 0) airCooldown--;

        // Its own strike: carried in behind it through the startup, then contact, then done.
        if (swing != null && lungeTicks > 0 && foe != null && isOnGround() && !busy) {
            Vec3d to = foe.getPos().subtract(getPos()).multiply(1, 0, 1);
            double dist = to.length();
            if (dist < (swing.leg() ? 1.4 : 1.1)) lungeLeft = 0; // there
            // Front-loaded: a burst in, tailing off into the strike.
            double step = lungeLeft * 2.0 / (lungeTicks + 1);
            lungeLeft -= step;
            lungeTicks--;
            if (dist > 1e-3) {
                Vec3d v = getVelocity();
                setVelocity(v.x * 0.25 + to.x / dist * step, v.y, v.z * 0.25 + to.z / dist * step);
                velocityModified = true;
            }
        } else if (swing == null) lungeTicks = 0;
        if (swing != null) {
            if (age == contactAt && foe != null && !busy) hit(foe, swing);
            // The hit can end the swing (parried, staggered, knocked down by a counter): check again.
            if (swing != null && moveAge() >= Math.round(swing.length() * 20)) cancelSwing();
        }

        // Calm: let it go after a while, or if they're gone or far off.
        if (foe != null && (!foe.isAlive() || foe.isSpectator() || age > calmAt || foe.squaredDistanceTo(this) > 20 * 20
            || (foe instanceof PlayerEntity pl && pl.isCreative() && age > calmAt - 100))) {
            foe = null;
            dataTracker.set(FOE, -1);
        }

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

    // ---- the brain: each tick it reads the fight and picks one thing to do ----

    /** What it means to do right now. */
    private enum Intent { CLOSE, SPACE, ATTACK, GUARD, AERIAL }

    private void fight(LivingEntity foe) {
        double d = Math.sqrt(squaredDistanceTo(foe));
        face(foe);
        Intent intent = decide(foe, d);
        switch (intent) {
            case CLOSE -> close(foe, d);
            case SPACE -> space(foe, d);
            case ATTACK -> attack(foe, d, false);
            case AERIAL -> aerial(foe, d);
            case GUARD -> getNavigation().stop();
        }
    }

    /**
     * Read the fight. Mid-strike or guarding: keep at it. Them up in the air within reach, or itself
     * already up: take it to the air. Out of range: close in. Too close: give itself room. In range
     * and ready: strike.
     */
    private Intent decide(LivingEntity foe, double d) {
        if (swing != null) return isOnGround() ? Intent.SPACE : Intent.AERIAL; // throwing something: feet planted (or still in the air)
        if (guarding()) return Intent.GUARD;
        boolean theyreUp = !foe.isOnGround() && foe.getY() - getY() > 0.8;
        if (!isOnGround() || (theyreUp && d < 4.5 && airCooldown <= 0)) return Intent.AERIAL;
        if (d > 1.7) return d < 2.3 && cooldown <= 1 ? Intent.ATTACK : Intent.CLOSE;
        if (d < 1.0) return Intent.SPACE;
        return Intent.ATTACK;
    }

    private void face(LivingEntity foe) {
        getLookControl().lookAt(foe, 40, 40);
        double dx = foe.getX() - getX(), dz = foe.getZ() - getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        setYaw(MathHelper.stepUnwrappedAngleTowards(getYaw(), yaw, 15));
        bodyYaw = MathHelper.stepUnwrappedAngleTowards(bodyYaw, getYaw(), 15);
        headYaw = getYaw();
    }

    /** Close the distance: walk in (run when far); no path (they're up on something): straight at them, hopping what's in the way. */
    private void close(LivingEntity foe, double d) {
        if (age % 5 == 0) getNavigation().startMovingTo(foe, d > 5 ? 1.15 : 0.9);
        if (getNavigation().isIdle() && d > 3.2) {
            getMoveControl().moveTo(foe.getX(), foe.getY(), foe.getZ(), d > 5 ? 1.15 : 0.9);
            stuck = horizontalCollision ? stuck + 1 : 0;
            if (stuck > 8 && isOnGround()) {
                getJumpControl().setActive();
                stuck = 0;
            }
        }
        cooldown--;
    }

    /** Hold its ground (planted while striking), easing back off if it's chest to chest. */
    private void space(LivingEntity foe, double d) {
        getNavigation().stop();
        stuck = 0;
        if (d < 1.0 && swing == null) {
            Vec3d away = getPos().subtract(foe.getPos()).multiply(1, 0, 1).normalize().multiply(0.06);
            addVelocity(away.x, 0, away.z);
        }
        cooldown--;
    }

    /**
     * Its turn: strings of one to three strikes; out of a block it counters (dropping the guard to hit
     * back); just out of reach it steps in as it throws. In the air, air strikes.
     */
    private void attack(LivingEntity foe, double d, boolean air) {
        getNavigation().stop();
        if (swing != null || --cooldown > 0) return;
        Fighting.Style s = Fighting.style("taijutsu");
        String[] picks = air ? new String[] {"air_jab", "air_hook", "air_knee", "whirl_kick"}
            : new String[] {"jab", "jab", "straight", "straight", "one_two", "lead_hook", "rear_hook", "front_kick", "roundhouse", "low_kick", "uppercut"};
        Fighting.Move m = s.move(s.index(picks[random.nextInt(picks.length)]));
        if (m == null) return;
        swing = m;
        // The lunge: carried in behind the strike through its startup, to just in front of them (never
        // into them), as a player's strike carries the player.
        if (!air && isOnGround()) {
            double stop = m.leg() ? 1.45 : 1.15, max = m.heavy() ? 1.6 : 0.9;
            lungeLeft = MathHelper.clamp(d - stop, 0, max);
            lungeTicks = Math.max(2, Math.round(m.contactOf(0) * 20 * 0.7f));
        }
        dataTracker.set(MOVE, Fighting.id(m));
        dataTracker.set(MOVE_AT, age);
        contactAt = age + Math.max(1, Math.round(m.contact() * 20));
        if (string <= 0) string = 1 + random.nextInt(3);
        string--;
        // The next strike of the string right after this one; a breather after the last.
        cooldown = Math.round(m.length() * 20) + (string > 0 ? 1 : 6 + random.nextInt(12));
    }

    /**
     * After them into the air: a jump at them, a second jump near the top of the first if they're still
     * above, and air strikes once they're in reach. Lands, and waits a moment before going up again.
     */
    private void aerial(LivingEntity foe, double d) {
        getNavigation().stop();
        if (isOnGround()) {
            if (swing != null) return;
            Vec3d to = foe.getPos().subtract(getPos()).multiply(1, 0, 1);
            to = to.lengthSquared() > 1e-4 ? to.normalize() : Vec3d.ZERO;
            setVelocity(to.x * 0.28, 0.5, to.z * 0.28);
            velocityModified = true;
            secondJump = true;
            airCooldown = 30;
            return;
        }
        double above = foe.getY() - getY();
        if (secondJump && getVelocity().y < 0.08 && above > 0.4) {
            // The second jump, with a flip.
            secondJump = false;
            Vec3d to = foe.getPos().subtract(getPos()).multiply(1, 0, 1);
            to = to.lengthSquared() > 1e-4 ? to.normalize() : Vec3d.ZERO;
            setVelocity(to.x * 0.22, 0.44, to.z * 0.22);
            velocityModified = true;
            Fighting.dodgeOf(this, 16);
        }
        if (d < 2.4) attack(foe, d, true);
    }

    /**
     * A strike is coming at it: guard it, or dodge it (back out of its reach, or aside from a straight
     * line), or misread it and do nothing, like a person would. Reaction time 100 to 400 ms.
     */
    private void evadeOrGuard(LivingEntity by, Fighting.Move m, boolean rightLimb) {
        float roll = random.nextFloat();
        if (roll > 0.8f) return; // didn't read it
        if (roll < 0.28f && isOnGround() && down() <= 0) {
            dodgeAt = age + 2 + random.nextInt(4);
            boolean straight = !m.leg() && (m.path() == Fighting.Path.STRAIGHT || m.path() == Fighting.Path.PALM);
            dodgeDir = straight && random.nextBoolean() ? (random.nextBoolean() ? 2 : 3) : 0;
            return;
        }
        raiseAt = age + 2 + random.nextInt(7);
        blockSide(by, m, rightLimb);
    }

    private void dodgeNow() {
        LivingEntity f = foe;
        Vec3d fwd = f != null ? f.getPos().subtract(getPos()).multiply(1, 0, 1) : Vec3d.fromPolar(0, getYaw());
        fwd = fwd.lengthSquared() > 1e-4 ? fwd.normalize() : Vec3d.ZERO;
        Vec3d left = new Vec3d(fwd.z, 0, -fwd.x);
        Vec3d push = switch (dodgeDir) {
            case 2 -> left.multiply(0.42);
            case 3 -> left.multiply(-0.42);
            default -> fwd.multiply(-0.5);
        };
        setVelocity(push.x, 0.08, push.z);
        velocityModified = true;
        Fighting.dodgeOf(this, dodgeDir);
        cooldown = Math.max(cooldown, 4); // a counter right after
    }

    private void hit(LivingEntity foe, Fighting.Move m) {
        // Only if the limb really reaches them: from the shoulder (a punch) or the hip (a kick) to the
        // nearest part of their body, no further than an arm or a leg, and in front of it.
        Vec3d fwd = Vec3d.fromPolar(0, getYaw());
        Vec3d from = getPos().add(0, m.leg() ? 0.8 : 1.4, 0).add(fwd.multiply(0.1));
        var b = foe.getBoundingBox();
        Vec3d near = new Vec3d(MathHelper.clamp(from.x, b.minX, b.maxX), MathHelper.clamp(from.y, b.minY, b.maxY), MathHelper.clamp(from.z, b.minZ, b.maxZ));
        Vec3d to = near.subtract(from);
        double limb = m.leg() ? 1.38 : 1.05; // a touch past the drawn limb: a strike that visibly meets counts
        if (to.length() > limb) return;
        Vec3d flat = new Vec3d(to.x, 0, to.z);
        if (flat.lengthSquared() > 0.04 && flat.normalize().dotProduct(fwd) < 0.2) return;
        // Struck where its aim was: the head for hooks, straights and high kicks, the body for the rest,
        // the legs for a low kick.
        Fighting.Part part = m.path() == Fighting.Path.LOW_KICK ? Fighting.Part.LEG
            : m.path() == Fighting.Path.FRONT_KICK || m.path() == Fighting.Path.KNEE ? Fighting.Part.TORSO
            : random.nextFloat() < 0.6f ? Fighting.Part.HEAD : Fighting.Part.TORSO;
        Vec3d look = foe.getPos().subtract(getPos()).multiply(1, 0, 1);
        look = look.lengthSquared() > 1e-4 ? look.normalize() : Vec3d.fromPolar(0, getYaw());
        Fighting.land(this, foe, m, 1, part, look, strikeSource());
    }

    /** Its own kind of damage: a fighter's strike, the same on every difficulty (mob attacks do nothing on Peaceful). */
    private net.minecraft.entity.damage.DamageSource strikeSource() {
        var key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE, net.minecraft.util.Identifier.of(KataCore.ID, "strike"));
        var type = getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE).getEntry(key);
        return type.isPresent() ? new net.minecraft.entity.damage.DamageSource(type.get(), this) : getDamageSources().mobAttack(this);
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
