package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import com.cbc_terminal_ballistics.util.IgnoredBlockSkip;
import net.minecraft.core.BlockPos;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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

    // Self-hit suppression: the cannon a shell was fired from is itself a contraption,
    // and without exclusion the shell "hits" its own hull right at the muzzle (vanilla
    // never saw those blocks because they are air in the world).
    @Unique
    private java.util.UUID ctb$sourceContraption;
    @Unique
    private long ctb$sourceExpiry = -1L;
    @Unique
    private int ctb$sourceChecksLeft = 3;

    // Same-cell dedupe: CBC's clip loop re-clips after each processed block; the world
    // has air at contraption spots, so vanilla returns MISS and re-substituting the
    // same cell re-processes it until the jam breaker kills the shell. One substitution
    // per (entity, cell) per tick is enough — across ticks repeat hits still count.
    @Unique
    private java.util.UUID ctb$lastCellEntity;
    @Unique
    private BlockPos ctb$lastCellPos;
    @Unique
    private long ctb$lastCellTick = -1L;

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
        Entity self = (Entity) (Object) this;
        // Ignored world blocks (data-driven list) are skipped in the clip itself so they are
        // never phased to air or removed — phasing disassembles Create contraptions anchored to
        // them and pops attached blocks. This must run before contraption substitution: the
        // vanilla clip result is what sees real world blocks such as a bearing anchor.
        if (self.level() != null) {
            BlockHitResult skipped = IgnoredBlockSkip.skipPastIgnored(self.level(), original);
            if (skipped != original) {
                ctb$pendingContraptionState = null;
                return skipped;
            }
        }
        if (!TBConfig.HIT_CREATE_CONTRAPTIONS.get()) {
            ctb$pendingContraptionState = null;
            return original;
        }

        if (self.level() == null) {
            ctb$pendingContraptionState = null;
            return original;
        }

        try {
            // Record (and expire) the source contraption to exclude from self-hits.
            long now = self.level().getGameTime();
            if (ctb$sourceExpiry > 0 && now > ctb$sourceExpiry) {
                ctb$sourceContraption = null;
                ctb$sourceExpiry = -1L;
            }
            if (ctb$sourceExpiry < 0 && ctb$sourceChecksLeft > 0) {
                ctb$sourceChecksLeft--;
                Entity source = ContraptionCompat.sourceContraptionCandidate(self);
                if (source != null) {
                    ctb$sourceContraption = source.getUUID();
                    ctb$sourceExpiry = now + 100L;
                    CTB_LOG.debug("[ctb] source contraption recorded {} (expires tick {})", ctb$sourceContraption, ctb$sourceExpiry);
                }
            }

            Vec3 from = self.position();
            Vec3 to = from.add(self.getDeltaMovement());
            ContraptionCompat.ContraptionHit hit = ContraptionCompat.raycast(self.level(), from, to, ctb$sourceContraption);
            if (hit == null) {
                ctb$pendingContraptionState = null;
                return original;
            }

            // Never substitute a contraption hit that is FARTHER than the vanilla
            // world hit on the same segment — that would swallow the nearer world
            // block entirely (e.g. a ship's hull plate in front of a deck-mounted
            // contraption). Only a nearer contraption hit (or a vanilla MISS)
            // replaces the clip result.
            if (original != null && original.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                if (from.distanceToSqr(hit.hitLocation()) > from.distanceToSqr(original.getLocation())) {
                    ctb$pendingContraptionState = null;
                    return original;
                }
            }

            // Same-cell dedupe: one substitution per (entity, local cell) per tick.
            // A repeat within the tick is the loop's re-clip after the cell was
            // already consumed — let the vanilla MISS end it instead.
            long nowTick = self.level().getGameTime();
            if (nowTick == ctb$lastCellTick
                && hit.entity() != null
                && hit.entity().getUUID().equals(ctb$lastCellEntity)
                && hit.localPos().equals(ctb$lastCellPos)) {
                ctb$pendingContraptionState = null;
                return original;
            }
            ctb$lastCellEntity = hit.entity() != null ? hit.entity().getUUID() : null;
            ctb$lastCellPos = hit.localPos();
            ctb$lastCellTick = nowTick;

            CTB_LOG.debug("[ctb] contraption hit! worldPos={} hitLoc={} dir={} normal={} state={}",
                hit.worldPos(), hit.hitLocation(), hit.direction(), hit.trueNormal(), hit.state());
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
