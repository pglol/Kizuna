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

    private static final Map<UUID, Integer> MASK = new HashMap<>();
    private static final Map<UUID, double[]> FALL = new HashMap<>(); // peak y (NaN on the ground), last y
    private static Path file;

    /** Falls up to this many blocks don't hurt. */
    static final double SAFE = 5;

    public static void register() {
        Registry.register(Registries.ITEM, Identifier.of(KataCore.ID, "bandage"), BANDAGE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(BANDAGE));
        ServerLifecycleEvents.SERVER_STARTED.register(Injuries::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        ServerTickEvents.END_SERVER_TICK.register(Injuries::tick);
        ServerPlayConnectionEvents.JOIN.register((h, sender, srv) -> srv.execute(() -> {
            ServerPlayerEntity p = h.getPlayer();
            apply(p);
            // A first visit: a couple of bandages to start with.
            if (!p.getCommandTags().contains("kata_bandaged")) {
                p.addCommandTag("kata_bandaged");
                p.giveItemStack(new ItemStack(BANDAGE, 3));
            }
        }));
        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> {
            if (!alive) MASK.remove(n.getUuid());
            apply(n);
            relay(n);
        });
    }

    public static int mask(LivingEntity e) {
        return MASK.getOrDefault(e.getUuid(), 0);
    }

    private static int legs(int m) {
        return Integer.bitCount(m & (L_LEG | R_LEG));
    }

    private static int arms(int m) {
        return Integer.bitCount(m & (L_ARM | R_ARM));
    }

    /** How hard a strike from `by` lands, with what's broken: a broken arm weakens punches, a broken leg kicks. */
    static float strikeScale(LivingEntity by, boolean leg) {
        int m = mask(by);
        return 1 - 0.3f * (leg ? legs(m) : arms(m));
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
        int m = mask(p);
        if ((m & bit) != 0) return;
        MASK.put(p.getUuid(), m | bit);
        apply(p);
        relay(p);
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.PLAYERS, 1f, 0.7f);
        p.sendMessage(Text.literal((bit == RIBS ? "Cracked your ribs" : "Broke your " + NAMES[Integer.numberOfTrailingZeros(bit)]) + "  ·  bandage it").formatted(Formatting.RED), true);
    }

    /** A bandage: sets up to two injuries (legs first, then ribs, then arms). Returns how many. */
    static int bandage(ServerPlayerEntity p) {
        int m = mask(p), healed = 0;
        for (int bit : new int[] {L_LEG, R_LEG, RIBS, L_ARM, R_ARM}) {
            if (healed >= 2) break;
            if ((m & bit) != 0) {
                m &= ~bit;
                healed++;
            }
        }
        if (healed == 0) return 0;
        if (m == 0) MASK.remove(p.getUuid());
        else MASK.put(p.getUuid(), m);
        apply(p);
        relay(p);
        return healed;
    }

    /** The stat side: a broken leg slows you (both, a lot) and you can't spring as high. */
    static void apply(ServerPlayerEntity p) {
        int l = legs(mask(p));
        EntityAttributeInstance speed = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SLOW);
            if (l > 0) speed.addTemporaryModifier(new EntityAttributeModifier(SLOW, l == 1 ? -0.28 : -0.5, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        EntityAttributeInstance jump = p.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
        if (jump != null) {
            jump.removeModifier(HOP);
            if (l > 0) jump.addTemporaryModifier(new EntityAttributeModifier(HOP, l == 1 ? -0.1 : -0.18, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    private static void relay(ServerPlayerEntity p) {
        Fighting.relayAround(p, KataNet.INJURY, mask(p));
    }

    private static void tick(MinecraftServer s) {
        int t = s.getTicks();
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            fall(p);
            if (t % 40 == 0 && MASK.containsKey(p.getUuid())) relay(p);
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
        MASK.clear();
        try {
            if (Files.exists(file))
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 2) MASK.put(UUID.fromString(p[0]), Integer.parseInt(p[1]));
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
            MASK.forEach((u, m) -> b.append(u).append(' ').append(m).append('\n'));
            Files.writeString(file, b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            KataCore.LOG.error("Could not save injuries.txt", e);
        }
    }
}
