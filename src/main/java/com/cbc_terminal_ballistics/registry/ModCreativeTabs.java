package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CBCTerminalBallistics.MOD_ID);

    public static final RegistryObject<CreativeModeTab> ARMOR_TAB =
            CREATIVE_MODE_TABS.register("armor", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CBCTerminalBallistics.MOD_ID + ".armor"))
                    .icon(() -> new ItemStack(ModItems.ARMOR_UPGRADER.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.COPYCAT_ARMOR_LAYER.get());
                        output.accept(ModItems.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get());
                        output.accept(ModItems.ARMOR_UPGRADER.get());
                        output.accept(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get());
                        output.accept(ModItems.BALLISTIC_TEST_LAUNCHER.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
