package com.cbc_terminal_ballistics.goggles;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.context.UseOnContext;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Ballistic goggles: while carried anywhere in the inventory, the holder
 * receives the artillery chase cam and terminal x-ray of every shell fired by
 * a cannon mount linked into the item (right-click a mount to link, sneak
 * right-click to unlink). CBC cannon shells carry no shooter identity, so
 * this explicit link is how redstone-fired defenses get watched without
 * leaking every nearby player's shells into your screen.
 */
public class BallisticGogglesItem extends Item {

    public BallisticGogglesItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        BlockState state = level.getBlockState(pos);
        if (!GoggleLinkManager.isCannonMount(state)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ItemStack stack = context.getItemInHand();
        if (player.isShiftKeyDown()) {
            if (GoggleLinkManager.removeLink(stack, (ServerLevel) level, pos)) {
                player.displayClientMessage(Component.literal(
                    "Goggle link removed. (" + GoggleLinkManager.linkCount(stack) + " linked)"), true);
            } else {
                player.displayClientMessage(Component.literal("This cannon mount was not linked."), true);
            }
        } else {
            String result = GoggleLinkManager.addLink(stack, (ServerLevel) level, pos);
            switch (result) {
                case "linked" -> player.displayClientMessage(Component.literal(
                    "Cannon mount linked to these goggles. (" + GoggleLinkManager.linkCount(stack)
                        + " linked) Their shells will now show your artillery and x-ray views."), true);
                case "already" -> player.displayClientMessage(Component.literal(
                    "This cannon mount is already linked."), true);
                case "full" -> player.displayClientMessage(Component.literal(
                    "These goggles cannot hold more than " + GoggleLinkManager.MAX_LINKS + " links."), true);
                default -> { }
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int count = GoggleLinkManager.linkCount(stack);
        tooltip.add(Component.literal("\u00A77Right-click a cannon mount to link its shells to these goggles."));
        tooltip.add(Component.literal("\u00A77Sneak right-click a linked mount to unlink it."));
        tooltip.add(Component.literal(count == 0 ? "\u00A78No cannon mounts linked" :
            "\u00A7b" + count + " cannon mount" + (count == 1 ? "" : "s") + " linked"));
    }
}
