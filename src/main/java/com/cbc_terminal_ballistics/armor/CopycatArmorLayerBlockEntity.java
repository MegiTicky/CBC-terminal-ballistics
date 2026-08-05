package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;

public class CopycatArmorLayerBlockEntity extends CCCopycatBlockEntity {
    public static final int MIN_LEVEL = CopycatArmorLayerItem.MIN_LEVEL;
    public static final int MAX_LEVEL = CopycatArmorLayerItem.MAX_LEVEL;
    public static final int TOUGHNESS_PER_LEVEL = CopycatArmorLayerItem.TOUGHNESS_PER_LEVEL;
    public static final String HAS_MATERIAL_TAG = "HasMaterial";
    private static final String COPYCATS_ITEM_TAG = "Item";

    private int armorLevel = MIN_LEVEL;

    public CopycatArmorLayerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.COPYCAT_ARMOR_LAYER.get(), pos, state);
    }

    public BlockState getCopiedMaterial() {
        BlockState material = getMaterial();
        return material == null || material.isAir() ? ArmorCopycatItemData.defaultMaterial() : material;
    }

    public void setCopiedMaterial(BlockState copiedMaterial) {
        BlockState material = copiedMaterial == null || copiedMaterial.isAir()
                ? ArmorCopycatItemData.defaultMaterial()
                : copiedMaterial;
        setMaterial(material);
        setConsumedItem(stackForMaterial(material));
    }

    public boolean hasCopiedMaterial() {
        return hasCustomMaterial();
    }

    public ItemStack removeCopiedMaterial() {
        if (!hasCopiedMaterial()) {
            return ItemStack.EMPTY;
        }
        Item item = getCopiedMaterial().getBlock().asItem();
        setMaterial(ArmorCopycatItemData.defaultMaterial());
        setConsumedItem(ItemStack.EMPTY);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public int getArmorLevel() {
        return ArmorCopycatItemData.clampLevel(armorLevel);
    }

    public void setArmorLevel(int armorLevel) {
        this.armorLevel = ArmorCopycatItemData.clampLevel(armorLevel);
        notifyUpdate();
    }

    public double getToughness() {
        return getArmorLevel() * TOUGHNESS_PER_LEVEL;
    }

    public void loadArmorFromItem(ItemStack stack) {
        this.armorLevel = CopycatArmorLayerItem.getArmorLevel(stack);

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(CopycatArmorLayerItem.MATERIAL_TAG)
                && (!data.contains(HAS_MATERIAL_TAG) || data.copyTag().getBoolean(HAS_MATERIAL_TAG))) {
            BlockState material = CopycatArmorLayerItem.getStoredMaterial(stack);
            setMaterial(material);
            setConsumedItem(stackForMaterial(material));
        } else {
            notifyUpdate();
        }
    }

    @Override
    public void saveToItem(ItemStack stack, HolderLookup.Provider provider) {
        super.saveToItem(stack, provider);
        CopycatArmorLayerItem.setArmorLevel(stack, getArmorLevel());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data ->
                data.update(tag -> {
                    tag.remove(CopycatArmorLayerItem.MATERIAL_TAG);
                    tag.remove(HAS_MATERIAL_TAG);
                }));
    }

    @Override
    public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        synthesizeCopycatsItemForLegacyMaterial(tag, registries);
        super.read(tag, registries, clientPacket);
        this.armorLevel = tag.contains(CopycatArmorLayerItem.ARMOR_LEVEL_TAG)
                ? tag.getInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG)
                : MIN_LEVEL;
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG, getArmorLevel());
    }

    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        tag.putInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG, getArmorLevel());
    }

    private static void synthesizeCopycatsItemForLegacyMaterial(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(CopycatArmorLayerItem.MATERIAL_TAG) || tag.contains(COPYCATS_ITEM_TAG)
                || (tag.contains(HAS_MATERIAL_TAG) && !tag.getBoolean(HAS_MATERIAL_TAG))) {
            return;
        }
        BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(CopycatArmorLayerItem.MATERIAL_TAG));
        ItemStack consumed = stackForMaterial(material);
        if (!consumed.isEmpty()) {
            tag.put(COPYCATS_ITEM_TAG, consumed.save(registries));
        }
    }

    private static ItemStack stackForMaterial(BlockState material) {
        if (material == null || material.isAir()) {
            return ItemStack.EMPTY;
        }
        Item item = material.getBlock().asItem();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }
}