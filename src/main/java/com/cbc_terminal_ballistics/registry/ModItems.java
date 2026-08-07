package com.cbc_terminal_ballistics.registry;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.armor.ArmorUpgraderItem;
import com.cbc_terminal_ballistics.armor.CopycatArmorLayerItem;
import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorItem;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CBCTerminalBallistics.MOD_ID);

    public static final DeferredItem<CopycatArmorLayerItem> COPYCAT_ARMOR_LAYER =
            ITEMS.register("copycat_armor_layer",
                    () -> new CopycatArmorLayerItem(ModBlocks.COPYCAT_ARMOR_LAYER.get(), new Item.Properties()));

    public static final DeferredItem<FramedCollapsibleCopycatArmorItem> FRAMED_COLLAPSIBLE_COPYCAT_ARMOR =
            ITEMS.register("framed_collapsible_copycat_armor_block",
                    () -> new FramedCollapsibleCopycatArmorItem(ModBlocks.FRAMED_COLLAPSIBLE_COPYCAT_ARMOR.get(), new Item.Properties()));

    public static final DeferredItem<ArmorUpgraderItem> ARMOR_UPGRADER =
            ITEMS.register("armor_upgrader",
                    () -> new ArmorUpgraderItem(new Item.Properties()));

    public static final DeferredItem<BallisticTestLauncherItem> BALLISTIC_TEST_LAUNCHER =
            ITEMS.register("ballistic_test_launcher",
                    () -> new BallisticTestLauncherItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}
}