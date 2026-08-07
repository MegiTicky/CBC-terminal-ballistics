package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.debug.BallisticTestLauncherItem;
import com.cbc_terminal_ballistics.registry.ModItems;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundSetLauncherVelocityPacket(int velocityMps) implements CustomPacketPayload {

    public static final Type<ServerboundSetLauncherVelocityPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "set_launcher_velocity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundSetLauncherVelocityPacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ServerboundSetLauncherVelocityPacket>) (buf, packet) -> packet.encode(buf),
        (StreamDecoder<RegistryFriendlyByteBuf, ServerboundSetLauncherVelocityPacket>) ServerboundSetLauncherVelocityPacket::decode
    );

    private void encode(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(velocityMps);
    }

    private static ServerboundSetLauncherVelocityPacket decode(RegistryFriendlyByteBuf buf) {
        return new ServerboundSetLauncherVelocityPacket(buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundSetLauncherVelocityPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            if (player == null) return;
            ItemStack stack = player.getMainHandItem();
            if (!stack.is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) stack = player.getOffhandItem();
            if (!stack.is(ModItems.BALLISTIC_TEST_LAUNCHER.get())) return;
            BallisticTestLauncherItem.setVelocity(stack, packet.velocityMps);
        });
    }
}