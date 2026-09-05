package com.cbc_terminal_ballistics.network;

import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The full War-Thunder-style x-ray diagram of one shell-vs-armour engagement:
 * the flight path, per-plate outcomes, the spall cone summary and every entity
 * the shell or its spall touched. All points are in world space when
 * {@code anchorUuid} is null; otherwise they are contraption-local and the
 * client re-anchors them to the moving vehicle each frame.
 */
public record ClientboundTerminalBallisticsPacket(int projectileId, String summary, String anchorUuid,
                                                  List<HitcamSample> path, List<Plate> plates,
                                                  Spall spall, List<EntityHit> entities) {

    public record Plate(Vec3 pos, String outcome, double damage, double toughness, boolean broken) {}

    public record EntityHit(int entityId, Vec3 pos, float damage, boolean killed) {}

    public record SpallBlock(Vec3 pos, boolean broken) {}

    public record Spall(Vec3 origin, Vec3 dir, double coneCos, double range, int fragments, List<SpallBlock> blocks) {}

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(projectileId);
        buf.writeUtf(summary, 256);
        buf.writeBoolean(anchorUuid != null);
        if (anchorUuid != null) {
            buf.writeUtf(anchorUuid, 64);
        }
        buf.writeVarInt(path.size());
        for (HitcamSample s : path) {
            buf.writeDouble(s.x());
            buf.writeDouble(s.y());
            buf.writeDouble(s.z());
            buf.writeFloat(s.ox());
            buf.writeFloat(s.oy());
            buf.writeFloat(s.oz());
        }
        buf.writeVarInt(plates.size());
        for (Plate p : plates) {
            writeVec3(buf, p.pos());
            buf.writeUtf(p.outcome(), 16);
            buf.writeDouble(p.damage());
            buf.writeFloat((float) p.toughness());
            buf.writeBoolean(p.broken());
        }
        buf.writeBoolean(spall != null);
        if (spall != null) {
            writeVec3(buf, spall.origin());
            writeVec3(buf, spall.dir());
            buf.writeFloat((float) spall.coneCos());
            buf.writeFloat((float) spall.range());
            buf.writeVarInt(spall.fragments());
            buf.writeVarInt(spall.blocks().size());
            for (SpallBlock b : spall.blocks()) {
                writeVec3(buf, b.pos());
                buf.writeBoolean(b.broken());
            }
        }
        buf.writeVarInt(entities.size());
        for (EntityHit e : entities) {
            buf.writeVarInt(e.entityId());
            writeVec3(buf, e.pos());
            buf.writeFloat(e.damage());
            buf.writeBoolean(e.killed());
        }
    }

    public static ClientboundTerminalBallisticsPacket decode(FriendlyByteBuf buf) {
        int projectileId = buf.readVarInt();
        String summary = buf.readUtf(256);
        String anchorUuid = buf.readBoolean() ? buf.readUtf(64) : null;
        int pathCount = buf.readVarInt();
        List<HitcamSample> path = new ArrayList<>(Math.min(pathCount, 1024));
        for (int i = 0; i < pathCount; i++) {
            path.add(new HitcamSample(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        int plateCount = buf.readVarInt();
        List<Plate> plates = new ArrayList<>(Math.min(plateCount, 64));
        for (int i = 0; i < plateCount; i++) {
            plates.add(new Plate(readVec3(buf), buf.readUtf(16), buf.readDouble(), buf.readFloat(), buf.readBoolean()));
        }
        Spall spall = null;
        if (buf.readBoolean()) {
            Vec3 origin = readVec3(buf);
            Vec3 dir = readVec3(buf);
            double coneCos = buf.readFloat();
            double range = buf.readFloat();
            int fragments = buf.readVarInt();
            int blockCount = buf.readVarInt();
            List<SpallBlock> blocks = new ArrayList<>(Math.min(blockCount, 64));
            for (int i = 0; i < blockCount; i++) {
                blocks.add(new SpallBlock(readVec3(buf), buf.readBoolean()));
            }
            spall = new Spall(origin, dir, coneCos, range, fragments, blocks);
        }
        int entityCount = buf.readVarInt();
        List<EntityHit> entities = new ArrayList<>(Math.min(entityCount, 32));
        for (int i = 0; i < entityCount; i++) {
            entities.add(new EntityHit(buf.readVarInt(), readVec3(buf), buf.readFloat(), buf.readBoolean()));
        }
        return new ClientboundTerminalBallisticsPacket(projectileId, summary, anchorUuid, path, plates, spall, entities);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            try {
                Class<?> handler = Class.forName("com.cbc_terminal_ballistics.client.ClientPacketHandlers");
                handler.getMethod("handleTerminalBallistics", ClientboundTerminalBallisticsPacket.class).invoke(null, this);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));
        ctx.get().setPacketHandled(true);
    }
}
