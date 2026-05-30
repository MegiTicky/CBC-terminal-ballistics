package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ClientAdvancedInspectionAccess {
    public static boolean isHoldingAdvancedTool() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get())
                || mc.player.getOffhandItem().is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get()));
    }

    public static InspectionSnapshot snapshot(BlockPos pos) {
        return ClientInspectionSnapshots.get(pos);
    }

    private ClientAdvancedInspectionAccess() {}
}
