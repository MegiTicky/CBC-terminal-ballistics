package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import com.cbc_terminal_ballistics.network.ServerboundInspectBlockPacket;
import com.cbc_terminal_ballistics.network.ServerboundSetLauncherVelocityPacket;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, value = Dist.CLIENT)
public final class ClientToolEvents {
    private static final ResourceLocation CBC_INSPECTION_TOOL = ResourceLocation.fromNamespaceAndPath("createbigcannons", "block_armor_inspection_tool");
    private static int requestCooldown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (requestCooldown-- > 0) return;
        requestCooldown = 5;
        if (!isHoldingAnyInspectionTool()) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        PacketDistributor.sendToServer(new ServerboundInspectBlockPacket(hit.getBlockPos(), hit.getDirection(), hit.getLocation()));
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isShiftKeyDown()) return;
        ItemStack stack = launcherStack();
        if (stack.isEmpty()) return;
        int delta = event.getScrollDeltaY() > 0 ? BallisticTestLauncherItem.STEP_MUZZLE_VELOCITY_MPS : -BallisticTestLauncherItem.STEP_MUZZLE_VELOCITY_MPS;
        int value = BallisticTestLauncherItem.clampVelocity(BallisticTestLauncherItem.velocity(stack) + delta);
        BallisticTestLauncherItem.setVelocity(stack, value);
        PacketDistributor.sendToServer(new ServerboundSetLauncherVelocityPacket(value));
        mc.player.displayClientMessage(Component.translatable("message.cbc_terminal_ballistics.ballistic_test_launcher.velocity", value).withStyle(ChatFormatting.YELLOW), true);
        event.setCanceled(true);
    }

    private static boolean isHoldingAnyInspectionTool() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        return isCbcInspectionTool(mc.player.getMainHandItem())
            || isCbcInspectionTool(mc.player.getOffhandItem());
    }

    private static boolean isCbcInspectionTool(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return CBC_INSPECTION_TOOL.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
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
