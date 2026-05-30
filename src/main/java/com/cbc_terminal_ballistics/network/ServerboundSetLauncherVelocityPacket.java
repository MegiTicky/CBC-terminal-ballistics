package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ServerboundSetLauncherVelocityPacket(int velocityMps) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(velocityMps);
    }

    public static ServerboundSetLauncherVelocityPacket decode(FriendlyByteBuf buf) {
        return new ServerboundSetLauncherVelocityPacket(buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ItemStack stack = player.getMainHandItem();
            if (!stack.is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) stack = player.getOffhandItem();
            if (!stack.is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) return;
            BallisticTestLauncherItem.setVelocity(stack, velocityMps);
        });
        ctx.get().setPacketHandled(true);
    }
}
