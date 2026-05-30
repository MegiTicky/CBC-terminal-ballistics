package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.registry.ModBlocks;
import com.cbc_terminal_ballistics.registry.ModBlockEntities;
import com.copycatsplus.copycats.content.copycat.layer.CopycatLayerModelCore;
import com.copycatsplus.copycats.foundation.copycat.model.CopycatModelCore;
import com.simibubi.create.CreateClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ArmorClientEvents {
    private ArmorClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), FramedCollapsibleCopycatArmorRenderer::new);
    }

    @SubscribeEvent
    public static void registerCopycatsLayerModel(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            CreateClient.MODEL_SWAPPER.getCustomBlockModels()
                    .register(ModBlocks.COPYCAT_ARMOR_LAYER.getId(), model -> CopycatModelCore.createModel(model, new CopycatLayerModelCore()));
            CreateClient.MODEL_SWAPPER.getCustomItemModels()
                    .register(ModBlocks.COPYCAT_ARMOR_LAYER.getId(), model -> CopycatModelCore.createModel(model, new CopycatLayerModelCore()));
        });
    }
}
