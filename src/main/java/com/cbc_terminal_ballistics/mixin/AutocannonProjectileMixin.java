package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.debug.TBDebug;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile", remap = false)
public abstract class AutocannonProjectileMixin {
    @Inject(method = "calculateBlockPenetration", at = @At("HEAD"), cancellable = true, remap = false)
    private void ctb$calculateBlockPenetration(@Coerce Object projectileContext, BlockState state, BlockHitResult blockHitResult, CallbackInfoReturnable<Object> cir) {
        TBDebug.mixinHit("autocannon", (Entity) (Object) this, state, blockHitResult.getBlockPos());
        Object result = TBImpactService.calculate((Entity) (Object) this, projectileContext, state, blockHitResult, true);
        if (result != null) cir.setReturnValue(result);
    }
}
