package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class FramedCollapsibleCopycatArmorBlockEntity extends BlockEntity {
    public static final int MIN_LEVEL = ArmorCopycatItemData.MIN_LEVEL;
    public static final int MAX_LEVEL = ArmorCopycatItemData.MAX_LEVEL;
    public static final int TOUGHNESS_PER_LEVEL = ArmorCopycatItemData.TOUGHNESS_PER_LEVEL;
    public static final String HAS_MATERIAL_TAG = "HasMaterial";

    private BlockState copiedMaterial = ArmorCopycatItemData.defaultMaterial();
    private boolean hasCopiedMaterial = false;
    private int armorLevel = MIN_LEVEL;
    private int packedOffsets = 0;

    public FramedCollapsibleCopycatArmorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), pos, state);
    }

    public BlockState getCopiedMaterial() {
        return copiedMaterial == null || copiedMaterial.isAir() ? ArmorCopycatItemData.defaultMaterial() : copiedMaterial;
    }

    public void setCopiedMaterial(BlockState copiedMaterial) {
        this.copiedMaterial = copiedMaterial == null || copiedMaterial.isAir() ? ArmorCopycatItemData.defaultMaterial() : copiedMaterial;
        this.hasCopiedMaterial = true;
        setChangedAndUpdate();
    }

    public boolean hasCopiedMaterial() {
        return hasCopiedMaterial;
    }

    public ItemStack removeCopiedMaterial() {
        if (!hasCopiedMaterial) {
            return ItemStack.EMPTY;
        }
        Item item = getCopiedMaterial().getBlock().asItem();
        this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
        this.hasCopiedMaterial = false;
        setChangedAndUpdate();
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public int getArmorLevel() {
        return ArmorCopycatItemData.clampLevel(armorLevel);
    }

    public void setArmorLevel(int armorLevel) {
        this.armorLevel = ArmorCopycatItemData.clampLevel(armorLevel);
        setChangedAndUpdate();
    }

    public double getToughness() {
        return getArmorLevel() * TOUGHNESS_PER_LEVEL;
    }

    public int getPackedOffsets() {
        return packedOffsets;
    }

    public void setPackedOffsets(int packedOffsets) {
        this.packedOffsets = ArmorCopycatItemData.sanitizeOffsets(packedOffsets);
        setChangedAndUpdate();
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
        setChangedAndUpdate();
    }

    public void loadFromItem(ItemStack stack) {
        setArmorLevel(FramedCollapsibleCopycatArmorItem.getArmorLevel(stack));
        if (stack.getTag() != null && stack.getTag().contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG)) {
            this.copiedMaterial = FramedCollapsibleCopycatArmorItem.getStoredMaterial(stack);
            this.hasCopiedMaterial = stack.getTag().contains(HAS_MATERIAL_TAG)
                    ? stack.getTag().getBoolean(HAS_MATERIAL_TAG)
                    : true;
        } else {
            this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
            this.hasCopiedMaterial = false;
        }
        setPackedOffsets(FramedCollapsibleCopycatArmorItem.getOffsets(stack));
    }

    @Override
    public void saveToItem(ItemStack stack) {
        super.saveToItem(stack);
        FramedCollapsibleCopycatArmorItem.setArmorLevel(stack, getArmorLevel());
        stack.getOrCreateTag().putBoolean(HAS_MATERIAL_TAG, hasCopiedMaterial);
        if (hasCopiedMaterial) {
            FramedCollapsibleCopycatArmorItem.setStoredMaterial(stack, getCopiedMaterial());
        } else if (stack.getTag() != null) {
            stack.getTag().remove(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG);
        }
        FramedCollapsibleCopycatArmorItem.setOffsets(stack, getPackedOffsets());
    }

    private void setChangedAndUpdate() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putBoolean(HAS_MATERIAL_TAG, hasCopiedMaterial);
        if (hasCopiedMaterial) {
            tag.put(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG, NbtUtils.writeBlockState(getCopiedMaterial()));
        }
        tag.putInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG, getArmorLevel());
        tag.putInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG, getPackedOffsets());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG)) {
            this.copiedMaterial = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                    tag.getCompound(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG));
            this.hasCopiedMaterial = tag.contains(HAS_MATERIAL_TAG)
                    ? tag.getBoolean(HAS_MATERIAL_TAG)
                    : true;
        } else {
            this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
            this.hasCopiedMaterial = false;
        }
        this.armorLevel = tag.contains(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                ? tag.getInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                : MIN_LEVEL;
        this.packedOffsets = tag.contains(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG)
                ? ArmorCopycatItemData.sanitizeOffsets(tag.getInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG))
                : 0;
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
