package com.cbc_terminal_ballistics.state;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Temporarily removes penetrated-but-not-destroyed blocks until the end of the server tick.
 * This lets CBC's projectile loop continue through an intact armor plate, then restores the
 * plate so penetration and destruction are no longer the same outcome.
 */
public final class TemporaryBlockPassage {
    private static final Map<ServerLevel, Map<BlockPos, SavedBlock>> PENDING = new WeakHashMap<>();

    public static void phaseForThisTick(ServerLevel level, BlockPos pos, BlockState state) {
        Map<BlockPos, SavedBlock> levelMap = PENDING.computeIfAbsent(level, l -> new HashMap<>());
        BlockPos immutable = pos.immutable();
        if (levelMap.containsKey(immutable)) return;

        CompoundTag blockEntityTag = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            blockEntityTag = be.saveWithFullMetadata(level.registryAccess());
            // Copycat blocks commonly drop their copied material from onRemove.
            // Detach the saved block entity first so this one-tick collision
            // bypass cannot duplicate its contents or copied material.
            level.removeBlockEntity(pos);
        }
        levelMap.put(immutable, new SavedBlock(state, blockEntityTag));
        int silentFlags = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), silentFlags);
    }

    public static void restore(ServerLevel level) {
        Map<BlockPos, SavedBlock> levelMap = PENDING.remove(level);
        if (levelMap == null || levelMap.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, SavedBlock>> it = levelMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, SavedBlock> entry = it.next();
            BlockPos pos = entry.getKey();
            SavedBlock saved = entry.getValue();
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
                int silentFlags = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
                level.setBlock(pos, saved.state(), silentFlags);
                if (saved.blockEntityTag() != null && level.getBlockEntity(pos) != null) {
                    level.getBlockEntity(pos).loadWithComponents(saved.blockEntityTag(), level.registryAccess());
                }
            }
            it.remove();
        }
    }

    private record SavedBlock(BlockState state, CompoundTag blockEntityTag) {}

    private TemporaryBlockPassage() {}
}
