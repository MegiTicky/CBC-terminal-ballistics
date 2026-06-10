package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FramedCollapsibleCopycatArmorBlockEntity extends CCCopycatBlockEntity {
    public static final int MIN_LEVEL = ArmorCopycatItemData.MIN_LEVEL;
    public static final int MAX_LEVEL = ArmorCopycatItemData.MAX_LEVEL;
    public static final int TOUGHNESS_PER_LEVEL = ArmorCopycatItemData.TOUGHNESS_PER_LEVEL;
    public static final String HAS_MATERIAL_TAG = "HasMaterial";
    private static final String COPYCATS_ITEM_TAG = "Item";

    private int armorLevel = MIN_LEVEL;
    private int packedOffsets = 0;

    public FramedCollapsibleCopycatArmorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), pos, state);
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

    public int getPackedOffsets() {
        return packedOffsets;
    }

    public void setPackedOffsets(int packedOffsets) {
        this.packedOffsets = ArmorCopycatItemData.sanitizeOffsets(packedOffsets);
        notifyUpdate();
    }

    public int getFaceOffset(Direction side) {
        return (packedOffsets >> (side.ordinal() * 4)) & 0xF;
    }

    public boolean changeFaceOffset(Direction side, boolean shrink) {
        int offset = getFaceOffset(side);
        int opposite = getFaceOffset(side.getOpposite());
        int next = shrink ? offset - 1 : offset + 1;
        if (next < 0 || next > 15 - opposite) {
            return false;
        }
        setFaceOffset(side, next);
        return true;
    }

    private void setFaceOffset(Direction side, int offset) {
        offset = Math.max(0, Math.min(15, offset));
        int idx = side.ordinal() * 4;
        int mask = 0xF << idx;
        packedOffsets = (packedOffsets & ~mask) | (offset << idx);
        notifyUpdate();
    }

    public void loadFromItem(ItemStack stack) {
        this.armorLevel = FramedCollapsibleCopycatArmorItem.getArmorLevel(stack);

        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG)
                && (!tag.contains(HAS_MATERIAL_TAG) || tag.getBoolean(HAS_MATERIAL_TAG))) {
            BlockState material = FramedCollapsibleCopycatArmorItem.getStoredMaterial(stack);
            setMaterial(material);
            setConsumedItem(stackForMaterial(material));
        } else {
            notifyUpdate();
        }
        setPackedOffsets(FramedCollapsibleCopycatArmorItem.getOffsets(stack));
    }

    @Override
    public void saveToItem(ItemStack stack) {
        super.saveToItem(stack);
        FramedCollapsibleCopycatArmorItem.setArmorLevel(stack, getArmorLevel());
        FramedCollapsibleCopycatArmorItem.setOffsets(stack, getPackedOffsets());
        if (stack.getTag() != null) {
            stack.getTag().remove(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG);
            stack.getTag().remove(HAS_MATERIAL_TAG);
        }
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public ItemRequirement getRequiredItems(BlockState state) {
        ItemStack consumedItem = getConsumedItem();
        if (consumedItem.isEmpty()) {
            return ItemRequirement.NONE;
        }
        return new ItemRequirement(ItemUseType.CONSUME, consumedItem);
    }

    @Override
    public void transform(@Nullable BlockEntity be, StructureTransform transform) {
        super.transform(be, transform);
        packedOffsets = transformOffsets(packedOffsets, transform);
        notifyUpdate();
    }

    private static int transformOffsets(int packedOffsets, StructureTransform transform) {
        if (transform.rotationAxis == null) {
            return packedOffsets;
        }
        byte[] offsets = unpackOffsets(packedOffsets);
        for (int i = 0; i < 6; i++) {
            Direction original = Direction.values()[i];
            Direction transformed = transformFacing(original, transform);
            offsets[transformed.ordinal()] = (byte) ((packedOffsets >> (i * 4)) & 0xF);
        }
        return packOffsets(offsets);
    }

    private static Direction transformFacing(Direction facing, StructureTransform transform) {
        facing = transform.mirrorFacing(facing);
        return transform.rotateFacing(facing);
    }

    private static byte[] unpackOffsets(int packedOffsets) {
        byte[] offsets = new byte[6];
        for (int i = 0; i < 6; i++) {
            offsets[i] = (byte) ((packedOffsets >> (i * 4)) & 0xF);
        }
        return offsets;
    }

    private static int packOffsets(byte[] offsets) {
        int packed = 0;
        for (int i = 0; i < 6; i++) {
            packed |= (offsets[i] & 0xF) << (i * 4);
        }
        return packed;
    }

    @Override
    public void read(CompoundTag tag, boolean clientPacket) {
        synthesizeCopycatsItemForLegacyMaterial(tag);
        super.read(tag, clientPacket);
        this.armorLevel = tag.contains(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                ? tag.getInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                : MIN_LEVEL;
        this.packedOffsets = tag.contains(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG)
                ? ArmorCopycatItemData.sanitizeOffsets(tag.getInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG))
                : 0;
    }

    @Override
    public void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG, getArmorLevel());
        tag.putInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG, getPackedOffsets());
    }

    @Override
    public void writeSafe(CompoundTag tag) {
        super.writeSafe(tag);
        tag.putInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG, getArmorLevel());
        tag.putInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG, getPackedOffsets());
    }

    private void synthesizeCopycatsItemForLegacyMaterial(CompoundTag tag) {
        if (!tag.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG) || tag.contains(COPYCATS_ITEM_TAG)
                || (tag.contains(HAS_MATERIAL_TAG) && !tag.getBoolean(HAS_MATERIAL_TAG))) {
            return;
        }
        BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG));
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