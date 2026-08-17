package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamEncoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundShellDebugTogglePacket(boolean enabled) implements CustomPacketPayload {
    public static final Type<ClientboundShellDebugTogglePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(CBCTerminalBallistics.MOD_ID, "shell_debug"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundShellDebugTogglePacket> STREAM_CODEC = StreamCodec.of(
        (StreamEncoder<RegistryFriendlyByteBuf, ClientboundShellDebugTogglePacket>) (buf, packet) -> buf.writeBoolean(packet.enabled()),
        (StreamDecoder<RegistryFriendlyByteBuf, ClientboundShellDebugTogglePacket>) buf -> new ClientboundShellDebugTogglePacket(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundShellDebugTogglePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleShellDebugToggle", ClientboundShellDebugTogglePacket.class).invoke(null, packet);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        });
    }
}
