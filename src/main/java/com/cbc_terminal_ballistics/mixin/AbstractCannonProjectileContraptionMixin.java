package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <h3>How it works</h3>
 * <p>{@code clipAndDamage()} performs a loop that each step does:</p>
 * <ol>
 *   <li>A block raycast ({@code Level.clip} with {@code COLLIDER/NONE}) — stores result
 *       as {@code BlockHitResult} ordinal 0.</li>
 *   <li>On the first step only, a fluid raycast ({@code Level.clip} with {@code OUTLINE/ANY}).</li>
 *   <li>If the block ray hit something, fetches the block state via
 *       {@code LevelChunk.getBlockState(hit.blockPos)} and calls
 *       {@code calculateBlockPenetration(ctx, state, hit)}.</li>
 * </ol>
 *
 * <p>Since vanilla {@code Level.clip} cannot see blocks inside Create contraptions
 * (contraption blocks are stored in entity-local data, not in the world chunk), the
 * contraption ray returns MISS and the projectile passes through.</p>
 *
 * <p>This mixin intercepts both the block clip result (ordinal 0) and the penetration
 * block state (ordinal 1) to substitute contraption hit data when available.</p>
 *
 * <p>Variable ordinals in {@code clipAndDamage} (type-filtered):</p>
 * <ul>
 *   <li>{@code BlockHitResult ordinal 0} = first block clip ({@code astore 21})</li>
 *   <li>{@code BlockState ordinal 0} = fluid state from {@code Level.getBlockState} ({@code astore 24})</li>
 *   <li>{@code BlockState ordinal 1} = penetration state from {@code LevelChunk.getBlockState} ({@code astore 28})</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile", remap = false)
public abstract class AbstractCannonProjectileContraptionMixin {

    @Unique
    private static final Logger CTB_LOG = LoggerFactory.getLogger("CBCTB/ContraptionMixin");

    @Unique
    private BlockState ctb$pendingContraptionState;

    @Inject(method = "clipAndDamage", at = @At("HEAD"))
    private void ctb$logHead(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        CTB_LOG.info("[ctb] clipAndDamage called. pos={} vel={} config={}",
            self.position(), self.getDeltaMovement(), TBConfig.HIT_CREATE_CONTRAPTIONS.get());
    }

    /**
     * Intercept the first BlockHitResult store (ordinal 0 = block clip result).
     * If a contraption hit is found, replace the vanilla MISS with the contraption's hit result.
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
            Vec3 from = self.position();
            Vec3 to = from.add(self.getDeltaMovement());
            ContraptionCompat.ContraptionHit hit = ContraptionCompat.raycast(self.level(), from, to);
            if (hit == null) {
                ctb$pendingContraptionState = null;
                return original;
            }

            CTB_LOG.info("[ctb] contraption hit! worldPos={} hitLoc={} dir={} state={}",
                hit.worldPos(), hit.hitLocation(), hit.direction(), hit.state());
            ctb$pendingContraptionState = hit.state();
            return hit.toBlockHitResult();
        } catch (Throwable t) {
            CTB_LOG.error("[ctb] contraption raycast threw", t);
        }

        ctb$pendingContraptionState = null;
        return original;
    }

    /**
     * Intercept the second BlockState store (ordinal 1 = penetration block state).
     * When a contraption hit was cached, substitute the contraption block state so that
     * {@code calculateBlockPenetration} receives the correct material properties.
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
