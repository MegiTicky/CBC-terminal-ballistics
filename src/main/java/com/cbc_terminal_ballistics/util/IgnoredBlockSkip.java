package com.cbc_terminal_ballistics.util;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.data.IgnoredBlockManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

/**
 * Lets CBC's clip loop treat ignored blocks (data-driven list) as if they do not exist
 * without ever mutating the world. The temporary "phase the block to air" approach used for
 * armor penetration is destructive for live Create blocks: an anchor read as air disassembles
 * its contraption, and the neighbor updates pop attached valves/levers. So instead of phasing,
 * the clip result for an ignored block is rewritten to a point just past the block's far side:
 * CBC then continues the ray beyond the block (hitting whatever is really behind it, no
 * tunneling) and never removes or notifies the block itself.
 *
 * <p>Consumed from a {@code clipAndDamage} {@code BlockHitResult} store interception in
 * {@code AbstractCannonProjectileContraptionMixin} (both branches — main now shares vs2.3's
 * fold-in of the skip into the contraption mixin).
 */
public final class IgnoredBlockSkip {
    private IgnoredBlockSkip() {}

    /**
     * Returns a synthetic {@link BlockHitResult} located just past the far side of the hit
     * block when that block is on the ignored list; returns {@code original} unchanged otherwise.
     */
    public static BlockHitResult skipPastIgnored(Level level, BlockHitResult original) {
        if (original.getType() != HitResult.Type.BLOCK) return original;
        if (!TBConfig.ENABLED.get()) return original;
        BlockPos pos = original.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (!IgnoredBlockManager.INSTANCE.isIgnored(state)) return original;

        // The hit face normal points back toward the shooter; travel continues inward.
        Vec3 inward = Vec3.atLowerCornerOf(original.getDirection().getOpposite().getNormal());
        Vec3 entry = original.getLocation();
        Vec3 exit = entry.add(inward.scale(1.05)); // fallback: treat the block as a full block deep
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            // Reversed clip (world coordinates) finds where the ray leaves the block's shape.
            BlockHitResult farFace = shape.clip(entry.add(inward.scale(2.0)), entry.add(inward.scale(0.001)), pos);
            if (farFace != null) exit = farFace.getLocation();
        }
        return new BlockHitResult(exit.add(inward.scale(0.05)), original.getDirection(), pos, false);
    }
}
