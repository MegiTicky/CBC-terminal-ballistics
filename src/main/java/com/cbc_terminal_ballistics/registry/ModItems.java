package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.ArmorUpgraderItem;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.debug.AdvancedBlockArmorInspectionToolItem;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import com.cbc_terminal_ballistics.goggles.BallisticGogglesItem;
import com.cbc_terminal_ballistics.goggles.SpotterGogglesItem;
import net.minecraft.world.item.Item;
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

    public static final RegistryObject<Item> BALLISTIC_GOGGLES =
            ITEMS.register("ballistic_goggles",
                    () -> new BallisticGogglesItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> SPOTTER_GOGGLES =
            ITEMS.register("spotter_goggles",
                    () -> new SpotterGogglesItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
