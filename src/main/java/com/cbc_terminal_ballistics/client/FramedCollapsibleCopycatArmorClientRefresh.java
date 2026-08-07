package com.cbc_terminal_ballistics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class FramedCollapsibleCopycatArmorClientRefresh {
    private FramedCollapsibleCopycatArmorClientRefresh() {
    }

    public static void refresh(BlockPos pos, BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.levelRenderer == null) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.level == null || minecraft.levelRenderer == null) {
                return;
            }
            minecraft.levelRenderer.blockChanged(minecraft.level, pos, state, state, Block.UPDATE_ALL);
            minecraft.levelRenderer.setBlockDirty(pos, state, state);
            minecraft.levelRenderer.setSectionDirtyWithNeighbors(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        });
    }
}
