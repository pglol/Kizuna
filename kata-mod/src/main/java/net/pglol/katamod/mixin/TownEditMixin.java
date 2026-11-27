package net.pglol.katamod.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Remembers blocks players change in the town (see TownEdits), so the edits can be exported exactly. */
@Mixin(World.class)
public abstract class TownEditMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("RETURN"), require = 0)
    private void kata$edited(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) net.pglol.katamod.TownEdits.changed((World) (Object) this, pos);
    }
}
