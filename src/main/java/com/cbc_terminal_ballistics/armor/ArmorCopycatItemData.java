package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ArmorCopycatItemData {
    public static final String ARMOR_LEVEL_TAG = "ArmorLevel";
    public static final String MATERIAL_TAG = "Material";
    public static final String OFFSETS_TAG = "Offsets";
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 20;
    public static final int TOUGHNESS_PER_LEVEL = 10;

    private ArmorCopycatItemData() {
    }

    public static int getArmorLevel(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        int level = data == null || !data.contains(ARMOR_LEVEL_TAG) ? MIN_LEVEL : data.copyTag().getInt(ARMOR_LEVEL_TAG);
        return clampLevel(level);
    }

    public static int getToughness(ItemStack stack) {
        return getArmorLevel(stack) * TOUGHNESS_PER_LEVEL;
    }

    public static void setArmorLevel(ItemStack stack, int level) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data ->
                data.update(tag -> {
                    tag.putInt(ARMOR_LEVEL_TAG, clampLevel(level));
                }));
    }

    public static BlockState getStoredMaterial(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(MATERIAL_TAG)) {
            return defaultMaterial();
        }
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), data.copyTag().getCompound(MATERIAL_TAG));
        return state == null || state.isAir() ? defaultMaterial() : state;
    }

    public static void setStoredMaterial(ItemStack stack, BlockState material) {
        if (material == null || material.isAir()) {
            material = defaultMaterial();
        }
        BlockState finalMaterial = material;
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data ->
                data.update(tag -> {
                    tag.put(MATERIAL_TAG, NbtUtils.writeBlockState(finalMaterial));
                }));
    }

    public static BlockState defaultMaterial() {
        Block copycatBase = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "copycat_base"));
        if (copycatBase != Blocks.AIR) {
            return copycatBase.defaultBlockState();
        }
        copycatBase = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("copycats", "copycat_base"));
        return copycatBase != Blocks.AIR ? copycatBase.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
    }

    public static int getOffsets(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null || !data.contains(OFFSETS_TAG) ? 0 : sanitizeOffsets(data.copyTag().getInt(OFFSETS_TAG));
    }

    public static void setOffsets(ItemStack stack, int packedOffsets) {
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data ->
                data.update(tag -> {
                    tag.putInt(OFFSETS_TAG, sanitizeOffsets(packedOffsets));
                }));
    }

    public static int clampLevel(int level) {
        int max = TBConfig.COPYCAT_ARMOR_MAX_LEVEL.get();
        return Math.max(MIN_LEVEL, Math.min(max, level));
    }

    public static int sanitizeOffsets(int packedOffsets) {
        int sanitized = 0;
        for (int i = 0; i < 6; i++) {
            int offset = (packedOffsets >> (i * 4)) & 0xF;
            sanitized |= offset << (i * 4);
        }
        return sanitized;
    }
}
