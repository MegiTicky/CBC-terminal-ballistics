package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TBNetwork {
    private TBNetwork() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar(CBCTerminalBallistics.MOD_ID).versioned("9");

            registrar.playToClient(
                ClientboundImpactMarksPacket.TYPE,
                ClientboundImpactMarksPacket.STREAM_CODEC,
                ClientboundImpactMarksPacket::handle
            );

            registrar.playToClient(
                ClientboundSpallConePacket.TYPE,
                ClientboundSpallConePacket.STREAM_CODEC,
                ClientboundSpallConePacket::handle
            );

            registrar.playToClient(
                ClientboundProjectileSlowdownPacket.TYPE,
                ClientboundProjectileSlowdownPacket.STREAM_CODEC,
                ClientboundProjectileSlowdownPacket::handle
            );

            registrar.playToServer(
                ServerboundInspectBlockPacket.TYPE,
                ServerboundInspectBlockPacket.STREAM_CODEC,
                ServerboundInspectBlockPacket::handle
            );

            registrar.playToClient(
                ClientboundInspectionSnapshotPacket.TYPE,
                ClientboundInspectionSnapshotPacket.STREAM_CODEC,
                ClientboundInspectionSnapshotPacket::handle
            );

            registrar.playToClient(
                ClientboundIntegrityProgressPacket.TYPE,
                ClientboundIntegrityProgressPacket.STREAM_CODEC,
                ClientboundIntegrityProgressPacket::handle
            );

            registrar.playToServer(
                ServerboundSetLauncherVelocityPacket.TYPE,
                ServerboundSetLauncherVelocityPacket.STREAM_CODEC,
                ServerboundSetLauncherVelocityPacket::handle
            );
        });
    }
}
