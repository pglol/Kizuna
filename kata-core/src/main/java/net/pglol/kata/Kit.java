package net.pglol.kata;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fighter's kit, on the server: no hotbar in the hands. The hand is inventory slot 8 and stays empty
 * (bare fists) unless a block has been readied to place or a weapon drawn; slots 0-2 are the three quick
 * slots (keys 1-3); everything else is storage. The paper doll: Mask (head), Shoulder, Elbow, Chest,
 * Gloves, Belt, Trousers (legs), Shoes (feet), and two weapon slots. Head, chest, legs and feet are
 * Minecraft's own armour slots; the rest are Kata's (an attachment kept with the player), each taking
 * items tagged kata_core:gear/<slot> (weapons: kata_core:gear/weapon, custom-animated ones only).
 * Creative and spectator players are left alone (builders keep the vanilla hotbar).
 */
public final class Kit {
    private Kit() {}

    public static final int HAND = 8;
    public static final int MASK = 0, SHOULDER = 1, ELBOW = 2, CHEST = 3, GLOVES = 4, BELT = 5, TROUSERS = 6, SHOES = 7, WEAPON_1 = 8, WEAPON_2 = 9, DOLL = 10;
    public static final String[] DOLL_NAMES = {"Mask", "Shoulder", "Elbow", "Chest", "Gloves", "Belt", "Trousers", "Shoes", "Weapon I", "Weapon II"};
    /** Kata's own gear slots, in order: shoulder, elbow, gloves, belt, weapon I, weapon II. */
    private static final int[] OWN = {SHOULDER, ELBOW, GLOVES, BELT, WEAPON_1, WEAPON_2};

    public static final TagKey<net.minecraft.item.Item> SHOULDER_TAG = tag("shoulder"), ELBOW_TAG = tag("elbow"), GLOVES_TAG = tag("gloves"),
        BELT_TAG = tag("belt"), WEAPON_TAG = tag("weapon");

    private static TagKey<net.minecraft.item.Item> tag(String s) {
        return TagKey.of(RegistryKeys.ITEM, Identifier.of(KataCore.ID, "gear/" + s));
    }

    public static final AttachmentType<List<ItemStack>> GEAR = AttachmentRegistry.<List<ItemStack>>builder()
        .persistent(ItemStack.OPTIONAL_CODEC.listOf())
        .copyOnDeath()
        .initializer(() -> empty())
        .buildAndRegister(Identifier.of(KataCore.ID, "gear"));

    private static List<ItemStack> empty() {
        List<ItemStack> l = new ArrayList<>();
        for (int i = 0; i < OWN.length; i++) l.add(ItemStack.EMPTY);
        return l;
    }

    /** Ops sent with KataNet.KIT (a = op | arg << 8, b = an inventory slot). */
    public static final int READY = 1, STOW = 2, USE = 3, QUICK = 4, EQUIP = 5, UNEQUIP = 6, DROP = 7, DRAW = 8;

    /** Hands holding something on purpose: a readied block (and how many it held then), or a drawn weapon. */
    private static final Map<UUID, Integer> PLACING = new HashMap<>();
    private static final Map<UUID, Integer> DRAWN = new HashMap<>();

    static boolean kata(ServerPlayerEntity p) {
        return !p.isCreative() && !p.isSpectator();
    }

    /** Which doll slot an item would go in, or -1. */
    public static int fits(net.minecraft.entity.LivingEntity p, ItemStack s) {
        if (s.isEmpty()) return -1;
        if (s.isIn(SHOULDER_TAG)) return SHOULDER;
        if (s.isIn(ELBOW_TAG)) return ELBOW;
        if (s.isIn(GLOVES_TAG)) return GLOVES;
        if (s.isIn(BELT_TAG)) return BELT;
        if (s.isIn(WEAPON_TAG)) return WEAPON_1;
        if (!(s.getItem() instanceof net.minecraft.item.Equipment)) return -1;
        return switch (p.getPreferredEquipmentSlot(s)) {
            case HEAD -> MASK;
            case CHEST -> CHEST;
            case LEGS -> TROUSERS;
            case FEET -> SHOES;
            default -> -1;
        };
    }

    private static EquipmentSlot armour(int doll) {
        return switch (doll) {
            case MASK -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case TROUSERS -> EquipmentSlot.LEGS;
            case SHOES -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private static int own(int doll) {
        for (int i = 0; i < OWN.length; i++) if (OWN[i] == doll) return i;
        return -1;
    }

    public static ItemStack doll(ServerPlayerEntity p, int doll) {
        EquipmentSlot a = armour(doll);
        if (a != null) return p.getEquippedStack(a);
        int i = own(doll);
        return i < 0 ? ItemStack.EMPTY : gear(p).get(i);
    }

    private static List<ItemStack> gear(ServerPlayerEntity p) {
        List<ItemStack> g = p.getAttachedOrCreate(GEAR);
        if (g.size() < OWN.length) {
            List<ItemStack> n = new ArrayList<>(g);
            while (n.size() < OWN.length) n.add(ItemStack.EMPTY);
            p.setAttached(GEAR, n);
            return n;
        }
        return g;
    }

    private static void setDoll(ServerPlayerEntity p, int doll, ItemStack s) {
        EquipmentSlot a = armour(doll);
        if (a != null) {
            p.equipStack(a, s);
            return;
        }
        int i = own(doll);
        if (i < 0) return;
        List<ItemStack> n = new ArrayList<>(gear(p));
        n.set(i, s);
        p.setAttached(GEAR, n);
        sync(p);
    }

    /** Send the player their own gear slots (Kata's ones; armour syncs itself). */
    public static void sync(ServerPlayerEntity p) {
        if (ServerPlayNetworking.canSend(p, KataNet.GearOf.ID)) ServerPlayNetworking.send(p, new KataNet.GearOf(new ArrayList<>(gear(p))));
    }

    private static boolean storage(int slot) {
        return slot >= 3 && slot < 36 && slot != HAND;
    }

    /** Into storage (filling stacks first, then empty slots); whatever doesn't fit is dropped at their feet. */
    public static void store(ServerPlayerEntity p, ItemStack s) {
        PlayerInventory inv = p.getInventory();
        for (int i = 3; i < 36 && !s.isEmpty(); i++) {
            if (!storage(i)) continue;
            ItemStack o = inv.getStack(i);
            if (!o.isEmpty() && ItemStack.areItemsAndComponentsEqual(o, s) && o.getCount() < o.getMaxCount()) {
                int n = Math.min(s.getCount(), o.getMaxCount() - o.getCount());
                o.increment(n);
                s.decrement(n);
            }
        }
        for (int i = 3; i < 36 && !s.isEmpty(); i++) {
            if (storage(i) && inv.getStack(i).isEmpty()) {
                inv.setStack(i, s.copy());
                s.setCount(0);
            }
        }
        if (!s.isEmpty()) p.dropItem(s.copy(), false, true);
    }

    static void handle(ServerPlayerEntity p, int a, int slot) {
        if (!kata(p)) return;
        int op = a & 0xFF, arg = a >> 8;
        PlayerInventory inv = p.getInventory();
        boolean okSlot = slot >= 0 && slot < 36;
        switch (op) {
            case READY -> {
                if (!okSlot || !(inv.getStack(slot).getItem() instanceof net.minecraft.item.BlockItem)) return;
                stow(p);
                ItemStack s = inv.getStack(slot);
                inv.setStack(slot, ItemStack.EMPTY);
                inv.setStack(HAND, s);
                PLACING.put(p.getUuid(), s.getCount());
            }
            case STOW -> stow(p);
            case USE -> {
                if (!okSlot) return;
                ItemStack s = inv.getStack(slot);
                boolean food = s.contains(net.minecraft.component.DataComponentTypes.FOOD), potion = s.getItem() instanceof net.minecraft.item.PotionItem;
                if (s.isEmpty() || !(food || potion)) return;
                if (food && !p.canConsume(s.get(net.minecraft.component.DataComponentTypes.FOOD).canAlwaysEat()) && !potion && p.getHealth() >= p.getMaxHealth()) return;
                ItemStack left = s.finishUsing(p.getWorld(), p);
                inv.setStack(slot, ItemStack.EMPTY);
                if (!left.isEmpty()) {
                    if (left == s) inv.setStack(slot, left);
                    else store(p, left);
                }
                // Food doesn't fill anyone up here (no hunger): it heals instead.
                if (food) p.heal(s.get(net.minecraft.component.DataComponentTypes.FOOD) == null ? 2 : Math.max(1, s.get(net.minecraft.component.DataComponentTypes.FOOD).nutrition()));
            }
            case QUICK -> {
                if (!okSlot || arg < 0 || arg > 2 || slot == HAND) return;
                ItemStack s = inv.getStack(slot), q = inv.getStack(arg);
                inv.setStack(arg, s);
                inv.setStack(slot, q);
            }
            case EQUIP -> {
                if (!okSlot) return;
                ItemStack s = inv.getStack(slot);
                int d = fits(p, s);
                if (d < 0) return;
                if (d == WEAPON_1 && !doll(p, WEAPON_1).isEmpty() && doll(p, WEAPON_2).isEmpty()) d = WEAPON_2;
                ItemStack was = doll(p, d);
                inv.setStack(slot, was.copy());
                setDoll(p, d, s.copy());
            }
            case UNEQUIP -> {
                if (arg < 0 || arg >= DOLL) return;
                ItemStack was = doll(p, arg);
                if (was.isEmpty()) return;
                setDoll(p, arg, ItemStack.EMPTY);
                store(p, was.copy());
            }
            case DROP -> {
                if (!okSlot) return;
                ItemStack s = inv.getStack(slot);
                if (s.isEmpty()) return;
                inv.setStack(slot, ItemStack.EMPTY);
                p.dropItem(s, true, true);
            }
            case DRAW -> {
                if (!inv.getStack(HAND).isEmpty()) {
                    stow(p);
                    return;
                }
                int w = !doll(p, WEAPON_1).isEmpty() ? WEAPON_1 : !doll(p, WEAPON_2).isEmpty() ? WEAPON_2 : -1;
                if (w < 0) return;
                inv.setStack(HAND, doll(p, w).copy());
                setDoll(p, w, ItemStack.EMPTY);
                DRAWN.put(p.getUuid(), w);
                p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 0.6f, 1.2f);
            }
            default -> { }
        }
    }

    /** Hands back to the stance: a readied block goes back to storage, a drawn weapon back to its slot. */
    static void stow(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        ItemStack h = inv.getStack(HAND);
        PLACING.remove(p.getUuid());
        Integer w = DRAWN.remove(p.getUuid());
        if (h.isEmpty()) return;
        inv.setStack(HAND, ItemStack.EMPTY);
        if (w != null && doll(p, w).isEmpty()) setDoll(p, w, h);
        else store(p, h);
    }

    /** Picked up with F: looked at, in reach, straight into storage (never the hands). */
    static void pickup(ServerPlayerEntity p, int entity) {
        if (!(p.getWorld().getEntityById(entity) instanceof ItemEntity item) || !item.isAlive()) return;
        if (item.squaredDistanceTo(p.getEyePos()) > 5.5 * 5.5) return;
        ItemStack s = item.getStack().copy();
        int n = s.getCount();
        if (kata(p)) store(p, s);
        else {
            p.getInventory().insertStack(s);
            if (!s.isEmpty()) {
                item.setStack(s);
                return;
            }
        }
        p.sendPickup(item, n);
        p.increaseStat(net.minecraft.stat.Stats.PICKED_UP.getOrCreateStat(item.getStack().getItem()), n);
        item.discard();
        Fighting.relayAround(p, KataNet.GRAB, 0);
    }

    /** Every few ticks: the hands stay empty unless something's held on purpose; a placed block clears them. */
    static void tick(MinecraftServer server) {
        if (server.getTicks() % 2 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!kata(p)) {
                PLACING.remove(p.getUuid());
                DRAWN.remove(p.getUuid());
                continue;
            }
            ItemStack h = p.getInventory().getStack(HAND);
            Integer placing = PLACING.get(p.getUuid());
            if (placing != null) {
                if (h.isEmpty() || h.getCount() < placing) stow(p); // placed one: back to the stance
                continue;
            }
            if (DRAWN.containsKey(p.getUuid())) {
                if (h.isEmpty()) DRAWN.remove(p.getUuid());
                continue;
            }
            if (!h.isEmpty()) {
                p.getInventory().setStack(HAND, ItemStack.EMPTY);
                store(p, h);
            }
        }
    }
}
