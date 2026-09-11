package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.hitcam.StreamPreferences;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server relay of the client's {@code hitcam.terrainStream}
 * preference. The server, not the client, sends the streamed chunks, and a
 * dedicated server cannot read a client config file — so the viewer tells the
 * server whether it wants terrain streaming at all (false = the viewer's
 * picture-in-picture renders from Distant Horizons LODs instead).
 */
public record ServerboundStreamPreferencePacket(boolean enabled) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static ServerboundStreamPreferencePacket decode(FriendlyByteBuf buf) {
        return new ServerboundStreamPreferencePacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) StreamPreferences.set(player.getUUID(), enabled);
        });
        ctx.get().setPacketHandled(true);
    }
}
