package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorModelProperties;
import com.copycatsplus.copycats.foundation.copycat.model.neoforge.CopycatModelNeoForge;
import net.minecraft.core.BlockPos;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

public class FramedCollapsibleCopycatArmorModel extends CopycatModelNeoForge {
    private final FramedCollapsibleCopycatArmorModelCore core;

    public FramedCollapsibleCopycatArmorModel(BakedModel originalModel, FramedCollapsibleCopycatArmorModelCore core) {
        super(originalModel, core, false);
        this.core = core;
    }

    @Override
    public ModelData.Builder gatherModelData(ModelData.Builder builder, BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData.Builder result = super.gatherModelData(builder, level, pos, state, modelData);
        Integer packedOffsets = modelData.get(FramedCollapsibleCopycatArmorModelProperties.PACKED_OFFSETS);
        if (packedOffsets == null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FramedCollapsibleCopycatArmorBlockEntity armor) {
                packedOffsets = armor.getPackedOffsets();
            }
        }
        return result.with(FramedCollapsibleCopycatArmorModelProperties.PACKED_OFFSETS, packedOffsets == null ? 0 : packedOffsets);
    }

    @Override
    protected void prepareModelCore(BlockState state, RandomSource rand, ModelData modelData) {
        super.prepareModelCore(state, rand, modelData);
        Integer packedOffsets = modelData.get(FramedCollapsibleCopycatArmorModelProperties.PACKED_OFFSETS);
        core.setData(packedOffsets == null ? 0 : packedOffsets);
    }
}
