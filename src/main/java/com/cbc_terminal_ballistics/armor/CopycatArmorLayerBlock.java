package com.cbc_terminal_ballistics.armor;

import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.copycatsplus.copycats.content.copycat.layer.CopycatLayerBlock;
import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * CBCTB's armor-bearing layer block, backed by Copycats+' copycat layer behavior.
 * CBCTB keeps its registry id and armor NBT; Copycats+ owns material copying,
 * stacking, wrench behavior, model assembly, and material synchronization.
 */
public class CopycatArmorLayerBlock extends CopycatLayerBlock {
    public CopycatArmorLayerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
            armorLayer.loadArmorFromItem(stack);
        }
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack stack = new ItemStack(asItem(), state.getValue(LAYERS));
        if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
            armorLayer.saveToItem(stack);
        }
        return List.of(stack);
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(asItem());
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof CopycatArmorLayerBlockEntity armorLayer) {
            armorLayer.saveToItem(stack);
        }
        return stack;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Class<CCCopycatBlockEntity> getBlockEntityClass() {
        return (Class) CopycatArmorLayerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CCCopycatBlockEntity> getBlockEntityType() {
        return ModBlockEntities.COPYCAT_ARMOR_LAYER.get();
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return super.getStateForPlacement(context);
    }
}
