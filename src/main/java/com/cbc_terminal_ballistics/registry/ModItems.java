package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.ArmorUpgraderItem;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.debug.AdvancedBlockArmorInspectionToolItem;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CBCTerminalBallistics.MOD_ID);

    public static final RegistryObject<Item> COPYCAT_ARMOR_LAYER =
            ITEMS.register("copycat_armor_layer",
                    () -> new CopycatArmorLayerItem(ModBlocks.COPYCAT_ARMOR_LAYER.get(), new Item.Properties()));

    public static final RegistryObject<Item> FRAMED_COLLAPSIBLE_COPYCAT_ARMOR =
            ITEMS.register("framed_collapsible_copycat_armor_block",
                    () -> new FramedCollapsibleCopycatArmorItem(ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), new Item.Properties()));

    public static final RegistryObject<Item> ARMOR_UPGRADER =
            ITEMS.register("armor_upgrader",
                    () -> new ArmorUpgraderItem(new Item.Properties()));

    public static final RegistryObject<Item> ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL =
            ITEMS.register("advanced_block_armor_inspection_tool",
                    () -> new AdvancedBlockArmorInspectionToolItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> BALLISTIC_TEST_LAUNCHER =
            ITEMS.register("ballistic_test_launcher",
                    () -> new BallisticTestLauncherItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    @Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class CreativeTabs {
        @SubscribeEvent
        public static void addToTabs(BuildCreativeModeTabContentsEvent event) {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(COPYCAT_ARMOR_LAYER);
                event.accept(FRAMED_COLLAPSIBLE_COPYCAT_ARMOR);
                event.accept(ARMOR_UPGRADER);
                event.accept(ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL);
                event.accept(BALLISTIC_TEST_LAUNCHER);
            }
        }
    }
}
