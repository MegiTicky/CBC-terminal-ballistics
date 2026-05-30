package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

        // Migration path for old CBCTB item stacks, which stored copied material
        // as top-level ItemStack NBT rather than Copycats+' BlockEntityTag data.
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(CopycatArmorLayerItem.MATERIAL_TAG)
                && (!tag.contains(HAS_MATERIAL_TAG) || tag.getBoolean(HAS_MATERIAL_TAG))) {
            BlockState material = CopycatArmorLayerItem.getStoredMaterial(stack);
            setMaterial(material);
            setConsumedItem(stackForMaterial(material));
        } else {
            notifyUpdate();
        }
    }

    @Override
    public void saveToItem(ItemStack stack) {
        super.saveToItem(stack);
        CopycatArmorLayerItem.setArmorLevel(stack, getArmorLevel());
        // Do not write old top-level Material/HasMaterial tags on new drops.
        if (stack.getTag() != null) {
            stack.getTag().remove(CopycatArmorLayerItem.MATERIAL_TAG);
            stack.getTag().remove(HAS_MATERIAL_TAG);
        }
    }

    @Override
    public void read(CompoundTag tag, boolean clientPacket) {
        synthesizeCopycatsItemForLegacyMaterial(tag);
        super.read(tag, clientPacket);
        this.armorLevel = tag.contains(CopycatArmorLayerItem.ARMOR_LEVEL_TAG)
                ? tag.getInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG)
                : MIN_LEVEL;
    }

    @Override
    public void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG, getArmorLevel());
    }

    @Override
    public void writeSafe(CompoundTag tag) {
        super.writeSafe(tag);
        tag.putInt(CopycatArmorLayerItem.ARMOR_LEVEL_TAG, getArmorLevel());
    }

    private static void synthesizeCopycatsItemForLegacyMaterial(CompoundTag tag) {
        if (!tag.contains(CopycatArmorLayerItem.MATERIAL_TAG) || tag.contains(COPYCATS_ITEM_TAG)
                || (tag.contains(HAS_MATERIAL_TAG) && !tag.getBoolean(HAS_MATERIAL_TAG))) {
            return;
        }
        BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(CopycatArmorLayerItem.MATERIAL_TAG));
        ItemStack consumed = stackForMaterial(material);
        if (!consumed.isEmpty()) {
            tag.put(COPYCATS_ITEM_TAG, consumed.save(new CompoundTag()));
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
