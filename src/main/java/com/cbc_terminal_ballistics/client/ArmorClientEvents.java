package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerBlockEntity;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlockEntity;
import com.cbc_terminal_ballistics.registry.ModBlocks;
import com.copycatsplus.copycats.content.copycat.layer.CopycatLayerModelCore;
import com.copycatsplus.copycats.foundation.copycat.model.CopycatModelCore;
import com.simibubi.create.CreateClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ArmorClientEvents {
    private ArmorClientEvents() {
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register((state, level, pos, tintIndex) -> {
            if (level == null || pos == null) {
                return -1;
            }
            if (level.getBlockEntity(pos) instanceof FramedCollapsibleCopycatArmorBlockEntity armor
                    && armor.hasCopiedMaterial()) {
                return event.getBlockColors().getColor(armor.getMaterial(), level, pos, tintIndex);
            }
            return -1;
        }, ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get());

        event.register((state, level, pos, tintIndex) -> {
            if (level == null || pos == null) {
                return -1;
            }
            if (level.getBlockEntity(pos) instanceof CopycatArmorLayerBlockEntity armorLayer
                    && armorLayer.hasCopiedMaterial()) {
                return event.getBlockColors().getColor(armorLayer.getCopiedMaterial(), level, pos, tintIndex);
            }
            return -1;
        }, ModBlocks.COPYCAT_ARMOR_LAYER.get());
    }

    @SubscribeEvent
    public static void registerCopycatsLayerModel(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            CreateClient.MODEL_SWAPPER.getCustomBlockModels()
                    .register(ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.getId(),
                            model -> new FramedCollapsibleCopycatArmorModel(model, new FramedCollapsibleCopycatArmorModelCore()));
            CreateClient.MODEL_SWAPPER.getCustomItemModels()
                    .register(ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.getId(),
                            model -> new FramedCollapsibleCopycatArmorModel(model, new FramedCollapsibleCopycatArmorModelCore()));
            CreateClient.MODEL_SWAPPER.getCustomBlockModels()
                    .register(ModBlocks.COPYCAT_ARMOR_LAYER.getId(), model -> CopycatModelCore.createModel(model, new CopycatLayerModelCore()));
            CreateClient.MODEL_SWAPPER.getCustomItemModels()
                    .register(ModBlocks.COPYCAT_ARMOR_LAYER.getId(), model -> CopycatModelCore.createModel(model, new CopycatLayerModelCore()));
        });
    }
}
