package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.hitcam.StreamPreferences;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server relay of the client's hitcam streaming preferences. The
 * server, not the client, sends the streamed chunks, and a dedicated server
 * cannot read a client config file — so the viewer tells the server what it
 * wants: {@code artillery} governs the chase-cam flight streaming
 * ({@code hitcam.terrainStream}; false = that view renders from Distant
 * Horizons LODs instead), {@code terminal} governs the terminal x-ray
 * streaming ({@code hitcam.terminalStream}), which is exempt from the
 * artillery switch.
 */
public record ServerboundStreamPreferencePacket(boolean artillery, boolean terminal) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(artillery);
        buf.writeBoolean(terminal);
    }

    public static ServerboundStreamPreferencePacket decode(FriendlyByteBuf buf) {
        return new ServerboundStreamPreferencePacket(buf.readBoolean(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) StreamPreferences.set(player.getUUID(), artillery, terminal);
        });
        ctx.get().setPacketHandled(true);
    }
}
