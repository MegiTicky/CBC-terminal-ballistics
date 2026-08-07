package com.cbc_terminal_ballistics.debug;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class AdvancedInspectionOverlayHooks {
    public static void addBlockArmorInfo(List<Component> tooltip, BlockPos pos) {
        if (!isClientHoldingAdvancedTool()) return;
        InspectionSnapshot snapshot = clientSnapshot(pos);
        if (snapshot == null) {
            tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.syncing").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.material", snapshot.materialId().toString()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.cbc", fmt(snapshot.armorToughness()), fmt(snapshot.armorHardness())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.material_stats", fmt(snapshot.ductility()), fmt(snapshot.brittleness()), fmt(snapshot.spallMultiplier())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.integrity", fmt(snapshot.integrityDamage()), fmt(snapshot.integrityThreshold())).withStyle(ChatFormatting.YELLOW));
        if (!snapshot.lastOutcome().isEmpty()) {
            tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.advanced_inspector.last_impact",
                    snapshot.lastOutcome(), snapshot.lastCaliber(), fmt(snapshot.lastVelocity()), fmt(snapshot.lastDamage()), snapshot.lastSpallFragments(), fmt(snapshot.lastMassRatio()), fmt(snapshot.lastSpallDamageModifier())).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static InspectionSnapshot clientSnapshot(BlockPos pos) {
        try {
            Class<?> cache = Class.forName("com.cbc_terminal_ballistics.client.ClientAdvancedInspectionAccess");
            Object value = cache.getMethod("snapshot", BlockPos.class).invoke(null, pos);
            return value instanceof InspectionSnapshot snapshot ? snapshot : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isClientHoldingAdvancedTool() {
        try {
            Class<?> access = Class.forName("com.cbc_terminal_ballistics.client.ClientAdvancedInspectionAccess");
            Object value = access.getMethod("isHoldingAdvancedTool").invoke(null);
            return value instanceof Boolean held && held;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private AdvancedInspectionOverlayHooks() {}
}
