package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
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
        CompoundTag tag = stack.getTag();
        int level = tag == null || !tag.contains(ARMOR_LEVEL_TAG) ? MIN_LEVEL : tag.getInt(ARMOR_LEVEL_TAG);
        return clampLevel(level);
    }

    public static int getToughness(ItemStack stack) {
        return getArmorLevel(stack) * TOUGHNESS_PER_LEVEL;
    }

    public static void setArmorLevel(ItemStack stack, int level) {
        stack.getOrCreateTag().putInt(ARMOR_LEVEL_TAG, clampLevel(level));
    }

    public static BlockState getStoredMaterial(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(MATERIAL_TAG)) {
            return defaultMaterial();
        }
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound(MATERIAL_TAG));
        return state == null || state.isAir() ? defaultMaterial() : state;
    }

    public static void setStoredMaterial(ItemStack stack, BlockState material) {
        if (material == null || material.isAir()) {
            material = defaultMaterial();
        }
        stack.getOrCreateTag().put(MATERIAL_TAG, NbtUtils.writeBlockState(material));
    }

    public static BlockState defaultMaterial() {
        ResourceLocation id = ResourceLocation.tryParse("create:copycat_base");
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
            return BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        }
        return Blocks.IRON_BLOCK.defaultBlockState();
    }

    public static int getOffsets(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null || !tag.contains(OFFSETS_TAG) ? 0 : sanitizeOffsets(tag.getInt(OFFSETS_TAG));
    }

    public static void setOffsets(ItemStack stack, int packedOffsets) {
        stack.getOrCreateTag().putInt(OFFSETS_TAG, sanitizeOffsets(packedOffsets));
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
