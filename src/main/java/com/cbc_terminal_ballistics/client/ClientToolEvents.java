package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import com.cbc_terminal_ballistics.network.ServerboundInspectBlockPacket;
import com.cbc_terminal_ballistics.network.ServerboundSetLauncherVelocityPacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientToolEvents {
    private static int requestCooldown;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (requestCooldown-- > 0) return;
        requestCooldown = 5;
        if (!isHoldingAdvancedTool()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        if (TBNetwork.CHANNEL != null) {
            TBNetwork.CHANNEL.sendToServer(new ServerboundInspectBlockPacket(hit.getBlockPos(), hit.getDirection(), hit.getLocation()));
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isShiftKeyDown()) return;
        ItemStack stack = launcherStack();
        if (stack.isEmpty()) return;
        int delta = event.getScrollDelta() > 0 ? BallisticTestLauncherItem.STEP_MUZZLE_VELOCITY_MPS : -BallisticTestLauncherItem.STEP_MUZZLE_VELOCITY_MPS;
        int value = BallisticTestLauncherItem.clampVelocity(BallisticTestLauncherItem.velocity(stack) + delta);
        BallisticTestLauncherItem.setVelocity(stack, value);
        if (TBNetwork.CHANNEL != null) TBNetwork.CHANNEL.sendToServer(new ServerboundSetLauncherVelocityPacket(value));
        mc.player.displayClientMessage(Component.translatable("message.cbc_terminal_ballistics.ballistic_test_launcher.velocity", value).withStyle(ChatFormatting.YELLOW), true);
        event.setCanceled(true);
    }

    private static boolean isHoldingAdvancedTool() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && (mc.player.getMainHandItem().is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get())
                || mc.player.getOffhandItem().is(ModItems.ADVANCED_BLOCK_ARMOR_INSPECTION_TOOL.get()));
    }

    private static ItemStack launcherStack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return ItemStack.EMPTY;
        if (mc.player.getMainHandItem().is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) return mc.player.getMainHandItem();
        if (mc.player.getOffhandItem().is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) return mc.player.getOffhandItem();
        return ItemStack.EMPTY;
    }

    private ClientToolEvents() {}
}
