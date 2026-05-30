package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlock;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CBCTerminalBallistics.MOD_ID);

    public static final RegistryObject<CopycatArmorLayerBlock> COPYCAT_ARMOR_LAYER =
            BLOCKS.register("copycat_armor_layer",
                    () -> new CopycatArmorLayerBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(1.5f, 20.0f)
                            .noOcclusion()));

    public static final RegistryObject<FramedCollapsibleCopycatArmorBlock> FRAMED_COLLAPSIBLE_COPYCAT_ARMOR =
            BLOCKS.register("framed_collapsible_copycat_armor_block",
                    () -> new FramedCollapsibleCopycatArmorBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(1.5f, 20.0f)
                            .dynamicShape()
                            .noOcclusion()));

    private ModBlocks() {
    }
}
