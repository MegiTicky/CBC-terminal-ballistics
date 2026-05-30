package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.debug.TBProjectileSlowdown;
import com.cbc_terminal_ballistics.network.ClientboundImpactMarksPacket;
import com.cbc_terminal_ballistics.network.ClientboundInspectionSnapshotPacket;
import com.cbc_terminal_ballistics.network.ClientboundProjectileSlowdownPacket;
import com.cbc_terminal_ballistics.network.ClientboundSpallConePacket;
import net.minecraft.client.Minecraft;

public final class ClientPacketHandlers {
    public static void handleImpactMarks(ClientboundImpactMarksPacket packet) {
        if (Minecraft.getInstance().level != null) ClientImpactMarks.accept(packet.pos(), packet.marks());
    }

    public static void handleSpallCone(ClientboundSpallConePacket packet) {
        ClientSpallConeVisuals.accept(packet);
    }

    public static void handleProjectileSlowdown(ClientboundProjectileSlowdownPacket packet) {
        TBProjectileSlowdown.setClientFactor(packet.factor());
    }

    public static void handleInspectionSnapshot(ClientboundInspectionSnapshotPacket packet) {
        if (Minecraft.getInstance().level != null) ClientInspectionSnapshots.accept(packet.snapshot());
    }

    private ClientPacketHandlers() {}
}
