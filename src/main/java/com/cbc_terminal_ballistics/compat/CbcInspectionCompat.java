package com.cbc_terminal_ballistics.compat;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public final class CbcInspectionCompat {
    private static boolean registered;

    public static void register() {
        if (registered) return;
        try {
            Class<?> clazz = Class.forName("rbasamoyai.createbigcannons.block_armor_properties.BlockArmorInspectionToolItem");
            Method addPredicate = clazz.getMethod("addIsHoldingPredicate", Predicate.class);
            Predicate<Player> predicate = CbcInspectionCompat::isHoldingAdvancedTool;
            addPredicate.invoke(null, predicate);
            registered = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            CBCTerminalBallistics.LOGGER.warn("Unable to register advanced armor inspection tool with CBC overlay", e);
        }
    }

    private static boolean isHoldingAdvancedTool(Player player) {
        if (player == null) return false;
        return isAdvanced(player.getMainHandItem()) || isAdvanced(player.getOffhandItem());
    }

    public static boolean isAdvanced(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get());
    }

    private CbcInspectionCompat() {}
}
