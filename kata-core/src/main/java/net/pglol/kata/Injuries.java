package net.pglol.kata;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Broken bones, and the falls that cause them. Kata works out fall damage itself (from how far you
 * really dropped, on the server): softer than Minecraft's, a caught landing much softer again, nothing
 * into water. A hard enough fall can break a leg (or both); a full heavy can crack ribs or break the
 * arm or leg it lands on. A broken leg slows you and stops you running (you limp), a broken arm weakens
 * its strikes (you hold it), cracked ribs make your breath come back slower. A bandage sets up to two
 * at a time. Everyone sees it: the mask is relayed to whoever can see you.
 */
public final class Injuries {
    private Injuries() {}

    public static final int L_LEG = 1, R_LEG = 2, L_ARM = 4, R_ARM = 8, RIBS = 16;
    private static final String[] NAMES = {"left leg", "right leg", "left arm", "right arm", "ribs"};

    public static final Item BANDAGE = new BandageItem(new Item.Settings().maxCount(16));
    private static final Identifier SLOW = Identifier.of(KataCore.ID, "injury_slow"), HOP = Identifier.of(KataCore.ID, "injury_jump");

    /** Ticks left to heal, per part (left leg, right leg, left arm, right arm, ribs). */
    private static final Map<UUID, int[]> LEFT = new HashMap<>();
    private static final Map<UUID, double[]> FALL = new HashMap<>(); // peak y (NaN on the ground), last y
    private static Path file;

    /** Falls up to this many blocks don't hurt. */
    static final double SAFE = 5;
    /** A break mends on its own in five minutes, hurting less as it goes. */
    static final int HEAL = 6000;

    public static void register() {
        Registry.register(Registries.ITEM, Identifier.of(KataCore.ID, "bandage"), BANDAGE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(BANDAGE));
        ServerLifecycleEvents.SERVER_STARTED.register(Injuries::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(Injuries::tick);
        ServerPlayConnectionEvents.JOIN.register((h, sender, srv) -> srv.execute(() -> {
            ServerPlayerEntity p = h.getPlayer();
            apply(p);
            relay(p);
            // A first visit: a few bandages to start with.
            if (!p.getCommandTags().contains("kata_bandaged")) {
                p.addCommandTag("kata_bandaged");
                p.giveItemStack(new ItemStack(BANDAGE, 3));
            }
        }));
        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> {
            if (!alive) LEFT.remove(n.getUuid());
            apply(n);
            relay(n);
        });
    }

    public static int mask(LivingEntity e) {
        int[] l = LEFT.get(e.getUuid());
        if (l == null) return 0;
        int m = 0;
        for (int i = 0; i < 5; i++) if (l[i] > 0) m |= 1 << i;
        return m;
    }

    /** How bad a part still is, 0 (fine) to 1 (just broken). */
    static float severity(LivingEntity e, int part) {
        int[] l = LEFT.get(e.getUuid());
        return l == null ? 0 : Math.min(1, l[part] / (float) HEAL);
    }

    /** What's relayed: four bits a part (0 healed, 15 fresh), left leg in the lowest. */
    private static int packed(LivingEntity e) {
        int[] l = LEFT.get(e.getUuid());
        if (l == null) return 0;
        int v = 0;
        for (int i = 0; i < 5; i++) if (l[i] > 0) v |= Math.max(1, Math.min(15, (int) Math.ceil(l[i] * 15.0 / HEAL))) << (4 * i);
        return v;
    }

    /** How hard a strike from `by` lands, with what's broken: a broken arm weakens punches, a broken leg kicks. */
    static float strikeScale(LivingEntity by, boolean leg) {
        float s = leg ? severity(by, 0) + severity(by, 1) : severity(by, 2) + severity(by, 3);
        return 1 - 0.3f * s;
    }

    /** A full heavy landing on someone can break what it hits. */
    static void struck(LivingEntity t, Fighting.Part part, float power, boolean heavy) {
        if (!(t instanceof ServerPlayerEntity p) || !heavy || power < 0.85f || p.isCreative()) return;
        float roll = p.getRandom().nextFloat();
        switch (part) {
            case TORSO -> { if (roll < 0.1f) injure(p, RIBS); }
            case ARM -> { if (roll < 0.14f) injure(p, p.getRandom().nextBoolean() ? L_ARM : R_ARM); }
            case LEG -> { if (roll < 0.12f) injure(p, p.getRandom().nextBoolean() ? L_LEG : R_LEG); }
            default -> { }
        }
    }

    static void injure(ServerPlayerEntity p, int bit) {
        int i = Integer.numberOfTrailingZeros(bit);
        int[] l = LEFT.computeIfAbsent(p.getUuid(), k -> new int[5]);
        if (l[i] > 0) {
            l[i] = HEAL; // hurt again: back to the start
            return;
        }
        l[i] = HEAL;
        apply(p);
        relay(p);
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1f, 0.7f);
        p.sendMessage(Text.literal((bit == RIBS ? "Cracked your ribs" : "Broke your " + NAMES[i]) + "  ·  it'll mend in a few minutes, faster bandaged").formatted(Formatting.RED), true);
    }

    /** A bandage: sets up to two injuries (legs first, then ribs, then arms). Returns how many. */
    static int bandage(ServerPlayerEntity p) {
        int[] l = LEFT.get(p.getUuid());
        if (l == null) return 0;
        int healed = 0;
        for (int i : new int[] {0, 1, 4, 2, 3}) {
            if (healed >= 2) break;
            if (l[i] > 0) {
                l[i] = 0;
                healed++;
            }
        }
        if (mask(p) == 0) LEFT.remove(p.getUuid());
        apply(p);
        relay(p);
        return healed;
    }

    /** The stat side, easing as it mends: a broken leg slows you (both, a lot) and you can't spring as high. */
    static void apply(ServerPlayerEntity p) {
        float legs = severity(p, 0) + severity(p, 1);
        EntityAttributeInstance speed = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SLOW);
            if (legs > 0.01f) speed.addTemporaryModifier(new EntityAttributeModifier(SLOW, -Math.min(0.55, 0.12 + 0.22 * legs), EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        EntityAttributeInstance jump = p.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(HOP);
            if (legs > 0.01f) jump.addTemporaryModifier(new EntityAttributeModifier(HOP, -Math.min(0.18, 0.04 + 0.08 * legs), EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void relay(ServerPlayerEntity p) {
        Fighting.relayAround(p, KataNet.INJURY, packed(p));
    }

    private static void tick(MinecraftServer s) {
        int t = s.getTicks();
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            fall(p);
            int[] l = LEFT.get(p.getUuid());
            if (l == null || t % 20 != 0) continue;
            // Mending, a second at a time.
            for (int i = 0; i < 5; i++) {
                if (l[i] <= 0) continue;
                l[i] = Math.max(0, l[i] - 20);
                if (l[i] == 0) p.sendMessage(Text.literal("Your " + NAMES[i] + (i == 4 ? " have" : " has") + " mended").formatted(Formatting.GRAY), true);
            }
            if (mask(p) == 0) LEFT.remove(p.getUuid());
            if (t % 100 == 0 || !LEFT.containsKey(p.getUuid())) apply(p);
            if (t % 40 == 0 || !LEFT.containsKey(p.getUuid())) relay(p);
        }
    }

    /** Fall damage, Kata's way: the real drop from the highest point since leaving the ground. */
    private static void fall(ServerPlayerEntity p) {
        double[] f = FALL.computeIfAbsent(p.getUuid(), k -> new double[] {Double.NaN, p.getY()});
        double y = p.getY();
        p.fallDistance = 0; // Minecraft's own fall damage is replaced by this
        boolean exempt = p.isCreative() || p.isSpectator() || p.getAbilities().flying || p.isFallFlying() || p.hasVehicle()
            || p.isTouchingWater() || p.isClimbing() || p.hasStatusEffect(StatusEffects.SLOW_FALLING) || p.hasStatusEffect(StatusEffects.LEVITATION)
            || Math.abs(y - f[1]) > 12 || clinging(p) || Fighting.airHeld(p.getUuid());
        f[1] = y;
        if (exempt) {
            f[0] = p.isOnGround() ? Double.NaN : y;
            return;
        }
        if (!p.isOnGround()) {
            f[0] = Double.isNaN(f[0]) ? y : Math.max(f[0], y);
            return;
        }
        if (Double.isNaN(f[0])) return;
        double drop = f[0] - y;
        f[0] = Double.NaN;
        if (drop <= SAFE) return;
        BlockState on = p.getWorld().getBlockState(p.getBlockPos().down());
        float soft = on.isOf(Blocks.HAY_BLOCK) ? 0.2f : on.isOf(Blocks.SLIME_BLOCK) ? 0 : on.isIn(net.minecraft.registry.tag.BlockTags.BEDS) ? 0.5f
            : on.isIn(net.minecraft.registry.tag.BlockTags.LEAVES) ? 0.6f : 1f;
        boolean caught = KataCore.braced(p);
        float dmg = (float) ((drop - SAFE) * 0.75) * soft * (caught ? KataCore.CAUGHT_DAMAGE : 1);
        if (dmg >= 0.5f) p.damage(p.getDamageSources().fall(), dmg);
        // A long drop can break a leg (caught: it takes a lot more).
        double breakAt = caught ? 16 : 11;
        if (drop >= breakAt && soft > 0.5f) {
            double chance = Math.min(0.9, 0.35 + (drop - breakAt) * 0.06);
            if (p.getRandom().nextDouble() < chance) {
                injure(p, p.getRandom().nextBoolean() ? L_LEG : R_LEG);
                if (drop >= breakAt + 11 && p.getRandom().nextDouble() < 0.5) injure(p, (mask(p) & L_LEG) != 0 ? R_LEG : L_LEG);
            }
        }
    }

    /** Hanging on a ledge or pulling up (their game says so): not falling. */
    private static boolean clinging(ServerPlayerEntity p) {
        int[] m = KataNet.MODES.get(p.getUuid());
        if (m == null) return false;
        int mode = m[0];
        return mode == 2 || mode == 4 || mode == 5 || mode == 7; // HANG, PULL, MANTLE, CORNER
    }

    private static void load(MinecraftServer s) {
        file = s.getSavePath(WorldSavePath.ROOT).resolve("kata_core").resolve("injuries.txt");
        LEFT.clear();
        try {
            if (Files.exists(file))
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 6) {
                        int[] l = new int[5];
                        for (int i = 0; i < 5; i++) l[i] = Integer.parseInt(p[i + 1]);
                        LEFT.put(UUID.fromString(p[0]), l);
                    } else if (p.length == 2) { // the older form: just what's broken
                        int m = Integer.parseInt(p[1]);
                        int[] l = new int[5];
                        for (int i = 0; i < 5; i++) if ((m & 1 << i) != 0) l[i] = HEAL;
                        LEFT.put(UUID.fromString(p[0]), l);
                    }
                }
        } catch (Exception e) {
            KataCore.LOG.warn("Could not read injuries.txt", e);
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            StringBuilder b = new StringBuilder();
            LEFT.forEach((u, l) -> {
                b.append(u);
                for (int v : l) b.append(' ').append(v);
                b.append('\n');
            });
            Files.writeString(file, b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            KataCore.LOG.error("Could not save injuries.txt", e);
        }
    }
}
