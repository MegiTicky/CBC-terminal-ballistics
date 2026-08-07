package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.cbc_terminal_ballistics.util.SablePhysicsCompat;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import com.simibubi.create.content.schematics.requirement.ItemRequirement.ItemUseType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import com.copycatsplus.copycats.foundation.copycat.model.neoforge.CopycatModelNeoForge;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FramedCollapsibleCopycatArmorBlockEntity extends CCCopycatBlockEntity {
    public static final int MIN_LEVEL = ArmorCopycatItemData.MIN_LEVEL;
    public static final int MAX_LEVEL = ArmorCopycatItemData.MAX_LEVEL;
    public static final int TOUGHNESS_PER_LEVEL = ArmorCopycatItemData.TOUGHNESS_PER_LEVEL;
    public static final String MATERIAL_TAG = "ArmorMaterial";
    public static final String HAS_MATERIAL_TAG = "HasMaterial";
    private static final String COPYCATS_ITEM_TAG = "Item";

    private int armorLevel = MIN_LEVEL;
    private int packedOffsets = 0;
    private BlockState copiedMaterial = ArmorCopycatItemData.defaultMaterial();
    private boolean armorHasCustomMaterial = false;
    private boolean sableCollisionRefreshPending = true;
    private int sableCollisionRefreshDelay;
    private int sableCollisionRefreshAttempts;
    private int lastSableColliderOffsets = Integer.MIN_VALUE;

    public FramedCollapsibleCopycatArmorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), pos, state);
    }

    public BlockState getCopiedMaterial() {
        return copiedMaterial != null && !copiedMaterial.isAir() ? copiedMaterial : ArmorCopycatItemData.defaultMaterial();
    }

    public void setCopiedMaterial(BlockState copiedMaterial) {
        BlockState material = copiedMaterial == null || copiedMaterial.isAir()
                ? ArmorCopycatItemData.defaultMaterial()
                : copiedMaterial;
        this.copiedMaterial = material;
        this.armorHasCustomMaterial = true;
        setMaterial(material);
        setConsumedItem(stackForMaterial(material));
        refreshModel();
    }

    public boolean hasCopiedMaterial() {
        return armorHasCustomMaterial;
    }

    public ItemStack removeCopiedMaterial() {
        if (!armorHasCustomMaterial) {
            return ItemStack.EMPTY;
        }
        BlockState current = getCopiedMaterial();
        if (current == null || current.isAir()) {
            return ItemStack.EMPTY;
        }
        Item item = current.getBlock().asItem();
        this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
        this.armorHasCustomMaterial = false;
        setMaterial(ArmorCopycatItemData.defaultMaterial());
        setConsumedItem(ItemStack.EMPTY);
        refreshModel();
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
        refreshModel();
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
        refreshModel();
    }

    public void loadFromItem(ItemStack stack) {
        this.armorLevel = FramedCollapsibleCopycatArmorItem.getArmorLevel(stack);

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG)
                && (!data.contains(HAS_MATERIAL_TAG) || data.copyTag().getBoolean(HAS_MATERIAL_TAG))) {
            BlockState material = FramedCollapsibleCopycatArmorItem.getStoredMaterial(stack);
            this.copiedMaterial = material;
            this.armorHasCustomMaterial = true;
            setMaterial(material);
            setConsumedItem(stackForMaterial(material));
        } else {
            this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
            this.armorHasCustomMaterial = false;
            setMaterial(ArmorCopycatItemData.defaultMaterial());
            setConsumedItem(ItemStack.EMPTY);
            refreshModel();
        }
        setPackedOffsets(FramedCollapsibleCopycatArmorItem.getOffsets(stack));
    }

    @Override
    public void saveToItem(ItemStack stack, HolderLookup.Provider provider) {
        super.saveToItem(stack, provider);
        FramedCollapsibleCopycatArmorItem.setArmorLevel(stack, getArmorLevel());
        FramedCollapsibleCopycatArmorItem.setOffsets(stack, getPackedOffsets());
        stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data ->
                data.update(tag -> {
                    tag.remove(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG);
                    tag.remove(HAS_MATERIAL_TAG);
                }));
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public ModelData getModelData() {
        return ModelData.builder()
                .with(CopycatModelNeoForge.MATERIAL_PROPERTY, getMaterial())
                .with(FramedCollapsibleCopycatArmorModelProperties.PACKED_OFFSETS, getPackedOffsets())
                .build();
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
        requestSableCollisionRefresh();
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
    public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        synthesizeCopycatsItemForLegacyMaterial(tag, registries);
        super.read(tag, registries, clientPacket);
        this.armorLevel = tag.contains(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                ? tag.getInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG)
                : MIN_LEVEL;
        this.packedOffsets = tag.contains(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG)
                ? ArmorCopycatItemData.sanitizeOffsets(tag.getInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG))
                : 0;
        if (tag.contains(MATERIAL_TAG)) {
            this.copiedMaterial = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                    tag.getCompound(MATERIAL_TAG));
            this.armorHasCustomMaterial = true;
        } else if (tag.contains(HAS_MATERIAL_TAG) && tag.getBoolean(HAS_MATERIAL_TAG)
                && tag.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG)) {
            // legacy tag
            this.copiedMaterial = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                    tag.getCompound(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG));
            this.armorHasCustomMaterial = true;
        } else {
            this.copiedMaterial = ArmorCopycatItemData.defaultMaterial();
            this.armorHasCustomMaterial = false;
        }
        if (armorHasCustomMaterial) {
            setMaterialInternal(getCopiedMaterial());
            setConsumedItemInternal(stackForMaterial(getCopiedMaterial()));
        } else {
            setMaterialInternal(ArmorCopycatItemData.defaultMaterial());
            setConsumedItemInternal(ItemStack.EMPTY);
        }
        refreshModel();
    }

    @Override
    public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG, getArmorLevel());
        tag.putInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG, getPackedOffsets());
        if (armorHasCustomMaterial && copiedMaterial != null && !copiedMaterial.isAir()) {
            tag.put(MATERIAL_TAG, NbtUtils.writeBlockState(copiedMaterial));
        }
    }

    @Override
    public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries);
        tag.putInt(FramedCollapsibleCopycatArmorItem.ARMOR_LEVEL_TAG, getArmorLevel());
        tag.putInt(FramedCollapsibleCopycatArmorItem.OFFSETS_TAG, getPackedOffsets());
    }

    private void synthesizeCopycatsItemForLegacyMaterial(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG) || tag.contains(COPYCATS_ITEM_TAG)
                || (tag.contains(HAS_MATERIAL_TAG) && !tag.getBoolean(HAS_MATERIAL_TAG))) {
            return;
        }
        BlockState material = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                tag.getCompound(FramedCollapsibleCopycatArmorItem.MATERIAL_TAG));
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

    private void refreshModel() {
        Level level = getLevel();
        requestModelDataUpdate();
        if (level != null) {
            BlockState state = getBlockState();
            if (level.isClientSide) {
                level.sendBlockUpdated(getBlockPos(), state, state, Block.UPDATE_ALL);
                refreshClientRenderer(getBlockPos(), state);
            } else {
                requestSableCollisionRefresh();
                notifyUpdate();
            }
            setChanged();
        }
    }

    public void requestSableCollisionRefresh() {
        sableCollisionRefreshPending = true;
        sableCollisionRefreshDelay = 0;
        sableCollisionRefreshAttempts = 0;
    }

    public void tickSableCollision() {
        if (!sableCollisionRefreshPending || !(getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (sableCollisionRefreshDelay > 0) {
            sableCollisionRefreshDelay--;
            return;
        }

        int offsets = getPackedOffsets();
        if (offsets == 0 && lastSableColliderOffsets == Integer.MIN_VALUE) {
            sableCollisionRefreshPending = false;
            return;
        }

        SablePhysicsCompat.RefreshResult result = SablePhysicsCompat.refreshBlockCollider(
                serverLevel,
                getBlockPos(),
                getBlockState(),
                getBlockState().getCollisionShape(serverLevel, getBlockPos()),
                offsets
        );
        if (result == SablePhysicsCompat.RefreshResult.UPDATED) {
            lastSableColliderOffsets = offsets;
            sableCollisionRefreshPending = false;
            sableCollisionRefreshAttempts = 0;
        } else if (result == SablePhysicsCompat.RefreshResult.NOT_APPLICABLE) {
            sableCollisionRefreshPending = false;
        } else if (++sableCollisionRefreshAttempts >= 100) {
            sableCollisionRefreshPending = false;
        } else {
            sableCollisionRefreshDelay = 4;
        }
    }

    private static void refreshClientRenderer(BlockPos pos, BlockState state) {
        try {
            Class<?> helper = Class.forName("com.cbc_terminal_ballistics.client.FramedCollapsibleCopycatArmorClientRefresh");
            helper.getMethod("refresh", BlockPos.class, BlockState.class).invoke(null, pos, state);
        } catch (ReflectiveOperationException ignored) {
            // Client-only renderer refresh is best-effort; normal block updates still keep data synced.
        }
    }
}
