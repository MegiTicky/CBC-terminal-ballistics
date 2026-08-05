package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class CommonForgeEvents {
    private CommonForgeEvents() {
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        if (!(state.getBlock() instanceof FramedCollapsibleCopycatArmorBlock)) {
            return;
        }
        if (!FramedCollapsibleCopycatArmorBlock.isCreateWrench(event.getItemStack())) {
            return;
        }

        boolean handled = FramedCollapsibleCopycatArmorBlock.handleWrenchLeftClick(
                level, event.getPos(), event.getEntity(), event.getFace());
        if (handled) {
            event.setCanceled(true);
        }
    }
}