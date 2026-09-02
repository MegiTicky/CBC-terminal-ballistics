package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into CBC's {@code AbstractCannonProjectile.clipAndDamage()} to add collision
 * detection against Create contraption blocks when the {@code hitCreateContraptions}
 * config is enabled.
 */
@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile", remap = false)
public abstract class AbstractCannonProjectileContraptionMixin {

    @Unique
    private BlockState ctb$pendingContraptionState;

    /**
     * Modify the first BlockResult (ordinal 0) after it is stored.
     * The first BlockHitResult assignment in clipAndDamage is the block raycast result.
     * When config is on, we run an additional contraption raycast and merge results.
     */
    @ModifyVariable(
        method = "clipAndDamage",
        at = @At(value = "STORE", ordinal = 0),
        argsOnly = false
    )
    private BlockHitResult ctb$modifyBlockResult(BlockHitResult original) {
        if (!TBConfig.HIT_CREATE_CONTRAPTIONS.get()) {
            ctb$pendingContraptionState = null;
            return original;
        }

        Entity self = (Entity) (Object) this;
        if (self.level() == null) {
            ctb$pendingContraptionState = null;
            return original;
        }

        try {
            ContraptionCompat.ContraptionHit hit = ContraptionCompat.raycast(self.level(), self.position(), self.position().add(self.getDeltaMovement()));
            if (hit == null) {
                ctb$pendingContraptionState = null;
                return original;
            }

            if (original.getType() == HitResult.Type.MISS) {
                ctb$pendingContraptionState = hit.state();
                return hit.toBlockHitResult();
            }

            double origDist = self.position().distanceToSqr(original.getLocation());
            double contraDist = self.position().distanceToSqr(hit.hitLocation());
            if (contraDist < origDist) {
                ctb$pendingContraptionState = hit.state();
                return hit.toBlockHitResult();
            }
        } catch (Throwable ignored) {
        }

        ctb$pendingContraptionState = null;
        return original;
    }

    /**
     * Modify the second BlockState (ordinal 1) after it is stored.
     * The first BlockState store is the fluid check; the second is the block penetration state.
     * When a contraption hit was cached, substitute the contraption block state.
     */
    @ModifyVariable(
        method = "clipAndDamage",
        at = @At(value = "STORE", ordinal = 1),
        argsOnly = false
    )
    private BlockState ctb$modifyBlockState(BlockState original) {
        if (ctb$pendingContraptionState != null) {
            BlockState state = ctb$pendingContraptionState;
            ctb$pendingContraptionState = null;
            return state;
        }
        return original;
    }
}
