package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isCannonWelder(event.getItemStack())) return;
        if (!TBImpactService.clearBlockImpactData(level, event.getPos())) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.getEntity().displayClientMessage(Component.literal("Repaired ballistic damage and removed impact debris."), true);
    }

    private static boolean isCannonWelder(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) != null
            && ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals("createbigcannons:cannon_welder");
    }

}
