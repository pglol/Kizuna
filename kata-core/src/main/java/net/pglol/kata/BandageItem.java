package net.pglol.kata;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** A bandage: hold use for a second and a half to set and bind up to two injuries (and heal a little). */
public final class BandageItem extends Item {
    public BandageItem(Settings settings) {
        super(settings);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.BRUSH;
    }

    @Override
    public int getMaxUseTime(ItemStack stack, LivingEntity user) {
        return 30;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        user.setCurrentHand(hand);
        return TypedActionResult.consume(user.getStackInHand(hand));
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!world.isClient && user instanceof ServerPlayerEntity p) {
            int n = Injuries.bandage(p);
            if (n == 0) {
                p.sendMessage(Text.literal("Nothing to bandage").formatted(Formatting.GRAY), true);
                return stack;
            }
            p.heal(2);
            world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.PLAYERS, 1f, 1.1f);
            p.sendMessage(Text.literal(n == 1 ? "Bandaged" : "Bandaged two injuries").formatted(Formatting.WHITE), true);
            stack.decrementUnlessCreative(1, p);
        }
        return stack;
    }
}
