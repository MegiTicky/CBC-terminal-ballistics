package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CBCTerminalBallistics.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CopycatArmorLayerBlockEntity>> COPYCAT_ARMOR_LAYER =
            BLOCK_ENTITIES.register("copycat_armor_layer",
                    () -> BlockEntityType.Builder.of(CopycatArmorLayerBlockEntity::new,
                            ModBlocks.COPYCAT_ARMOR_LAYER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FramedCollapsibleCopycatArmorBlockEntity>> FRAMED_COLLAPSIBLE_COPYCAT_ARMOR =
            BLOCK_ENTITIES.register("framed_collapsible_copycat_armor_block",
                    () -> BlockEntityType.Builder.of(FramedCollapsibleCopycatArmorBlockEntity::new,
                            ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get()).build(null));

    private ModBlockEntities() {}
}