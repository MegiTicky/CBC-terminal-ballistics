package com.cbc_terminal_ballistics.armor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FramedCollapsibleCopycatArmorItem extends BlockItem {
    public static final String ARMOR_LEVEL_TAG = ArmorCopycatItemData.ARMOR_LEVEL_TAG;
    public static final String MATERIAL_TAG = ArmorCopycatItemData.MATERIAL_TAG;
    public static final String OFFSETS_TAG = ArmorCopycatItemData.OFFSETS_TAG;
    public static final int MIN_LEVEL = ArmorCopycatItemData.MIN_LEVEL;
    public static final int MAX_LEVEL = ArmorCopycatItemData.MAX_LEVEL;
    public static final int TOUGHNESS_PER_LEVEL = ArmorCopycatItemData.TOUGHNESS_PER_LEVEL;

    public FramedCollapsibleCopycatArmorItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static int getArmorLevel(ItemStack stack) {
        return ArmorCopycatItemData.getArmorLevel(stack);
    }

    public static int getToughness(ItemStack stack) {
        return ArmorCopycatItemData.getToughness(stack);
    }

    public static void setArmorLevel(ItemStack stack, int level) {
        ArmorCopycatItemData.setArmorLevel(stack, level);
    }

    public static BlockState getStoredMaterial(ItemStack stack) {
        return ArmorCopycatItemData.getStoredMaterial(stack);
    }

    public static void setStoredMaterial(ItemStack stack, BlockState material) {
        ArmorCopycatItemData.setStoredMaterial(stack, material);
    }

    public static int getOffsets(ItemStack stack) {
        return ArmorCopycatItemData.getOffsets(stack);
    }

    public static void setOffsets(ItemStack stack, int packedOffsets) {
        ArmorCopycatItemData.setOffsets(stack, packedOffsets);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        int armorLevel = getArmorLevel(stack);
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.copycat_armor_layer.level", armorLevel)
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.copycat_armor_layer.toughness", armorLevel * TOUGHNESS_PER_LEVEL)
                .withStyle(ChatFormatting.DARK_GREEN));
        tooltip.add(Component.translatable("tooltip.cbc_terminal_ballistics.framed_collapsible_copycat_armor_block.deform")
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
