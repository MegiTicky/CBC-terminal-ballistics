package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.util.IgnoredBlockSkip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Mixin into CBC's {@code AbstractCannonProjectile.clipAndDamage()} to skip blocks on the
 * data-driven ignored list directly in the clip result, so projectiles pass through them
 * without CBC ever removing or notifying the block. Phasing the block to air instead would
 * disassemble Create contraptions anchored to it and pop attached valves/levers.
 *
 * <p>The {@code BlockHitResult} from the vanilla {@code Level.clip} is stored once in
 * {@code clipAndDamage} (type-filtered ordinal 0). When it hits an ignored world block,
 * {@link IgnoredBlockSkip} rewrites it to a point just past the block's far side; the loop
 * then continues the ray behind the block and re-fetches the ignored state, which
 * {@code TBImpactService.calculate} answers with a pure PENETRATE.
 */
@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile", remap = false)
public abstract class AbstractCannonProjectileIgnoredBlockMixin {

    @ModifyVariable(
        method = "clipAndDamage",
        at = @At(value = "STORE", ordinal = 0),
        argsOnly = false
    )
    private BlockHitResult ctb$skipIgnoredBlocks(BlockHitResult original) {
        if (original == null) return null;
        Entity self = (Entity) (Object) this;
        if (self.level() == null) return original;
        return IgnoredBlockSkip.skipPastIgnored(self.level(), original);
    }
}
