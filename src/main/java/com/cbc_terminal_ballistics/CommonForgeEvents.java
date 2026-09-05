package com.cbc_terminal_ballistics;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.cbc_terminal_ballistics.ballistics.TBImpactService;
import com.cbc_terminal_ballistics.hitcam.TerminalBallisticsRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
        boolean shift = event.getEntity().isShiftKeyDown();
        BlockPos targetPos = event.getPos();
        boolean changed = clearWelderData(level, targetPos, shift);
        if (!changed && event.getFace() != null) {
            // A thin outer layer can receive the click while the shell is stored on the block behind it.
            targetPos = targetPos.relative(event.getFace().getOpposite());
            changed = clearWelderData(level, targetPos, shift);
        }
        if (!changed) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.getEntity().displayClientMessage(Component.literal(
            shift ? "Removed embedded shells." : "Repaired ballistic damage and removed impact debris."), true);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct != null) {
            TerminalBallisticsRecorder.onDirectEntityHit(direct, event.getEntity(), event.getAmount());
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (direct != null) {
            TerminalBallisticsRecorder.onDirectEntityKill(direct, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        TerminalBallisticsRecorder.onEntityRemoved(event.getEntity());
    }

    private static boolean clearWelderData(ServerLevel level, BlockPos pos, boolean shellsOnly) {
        return shellsOnly ? TBImpactService.clearEmbeddedShellsOnly(level, pos)
            : TBImpactService.clearBlockImpactData(level, pos);
    }

    private static boolean isCannonWelder(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) != null
            && ForgeRegistries.ITEMS.getKey(stack.getItem()).toString().equals("createbigcannons:cannon_welder");
    }

}
