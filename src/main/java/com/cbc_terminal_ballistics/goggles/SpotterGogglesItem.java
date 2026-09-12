package com.cbc_terminal_ballistics.goggles;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Spotter goggles: the opt-in version of the old "nearby shells open a view"
 * behaviour. While carried anywhere in the inventory, shells spawning within
 * the configured spotter radius of the holder open their views — including
 * other players' shells. The linked ballistic goggles are the selective
 * counterpart; this item is for players who want to watch everything around
 * them.
 */
public class SpotterGogglesItem extends Item {

    public SpotterGogglesItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("\u00A77Carry these to see the views of every shell fired near you."));
        tooltip.add(Component.literal("\u00A78Other players' shells included \u2014 use ballistic goggles"));
        tooltip.add(Component.literal("\u00A78for specific cannons instead."));
    }
}
