package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CBCTerminalBallistics.MOD_ID);

    public static final RegistryObject<BlockEntityType<CopycatArmorLayerBlockEntity>> COPYCAT_ARMOR_LAYER =
            BLOCK_ENTITIES.register("copycat_armor_layer",
                    () -> BlockEntityType.Builder.of(CopycatArmorLayerBlockEntity::new,
                            ModBlocks.COPYCAT_ARMOR_LAYER.get()).build(null));

    public static final RegistryObject<BlockEntityType<FramedCollapsibleCopycatArmorBlockEntity>> FRAMED_COLLAPSIBLE_COPYCAT_ARMOR =
            BLOCK_ENTITIES.register("framed_collapsible_copycat_armor_block",
                    () -> BlockEntityType.Builder.of(FramedCollapsibleCopycatArmorBlockEntity::new,
                            ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get()).build(null));

    private ModBlockEntities() {
    }
}
