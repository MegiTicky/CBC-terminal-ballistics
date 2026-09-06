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
 * {@code anchor} is null; otherwise they are vehicle-local and the client
 * re-anchors them to the moving vehicle each frame.
 */
public record ClientboundTerminalBallisticsPacket(int projectileId, String entityTypeKey, String summary, Anchor anchor,
                                                  List<HitcamSample> path, List<Plate> plates,
                                                  Spall spall, List<EntityHit> entities) {

    /**
     * {@code blockStateId} is the struck block's state id ({@code Block.getId});
     * the client renders the plate highlight as that block's real shape. A
     * negative id means unknown (full-cube fallback).
     */
    public record Plate(Vec3 pos, String outcome, double damage, double toughness, boolean broken, int blockStateId) {}

    public record EntityHit(int entityId, Vec3 pos, float damage, boolean killed) {}

    /** {@code armorLike} is true when the spall-struck block is itself armour/hull, not internal structure. */
    public record SpallBlock(Vec3 pos, boolean broken, boolean armorLike) {}

    /**
     * {@code rayStops} holds the display-ray stop distances the server raycast
     * for the client's deterministic cone sampling (absolute block distances;
     * {@code >= range} means unobstructed), so drawn spall rays terminate at
     * the geometry that actually stopped them.
     */
    public record Spall(Vec3 origin, Vec3 dir, double coneCos, double range, int fragments,
                        List<SpallBlock> blocks, float[] rayStops) {}

    /**
     * How the packet's coordinates map back to the world. {@code CONTRAPTION}
     * anchors to a Create contraption entity by UUID (points were converted to
     * contraption-local space). {@code VS} anchors to a Valkyrien Skies ship
     * via one ship-local reference point ({@code localRef}, in shipyard
     * coordinates) and its world position at snapshot time ({@code worldRef});
     * the client resolves the live ship and re-derives both per frame. A null
     * anchor means every point is plain world space.
     */
    public record Anchor(String kind, String uuid, Vec3 localRef, Vec3 worldRef) {
        public static Anchor contraption(String uuid) {
            return new Anchor("CONTRAPTION", uuid, null, null);
        }

        public static Anchor vs(Vec3 localRef, Vec3 worldRef) {
            return new Anchor("VS", null, localRef, worldRef);
        }
    }

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
        buf.writeUtf(entityTypeKey, 128);
        buf.writeUtf(summary, 256);
        buf.writeBoolean(anchor != null);
        if (anchor != null) {
            buf.writeUtf(anchor.kind(), 16);
            if ("CONTRAPTION".equals(anchor.kind())) {
                buf.writeUtf(anchor.uuid(), 64);
            } else if ("VS".equals(anchor.kind())) {
                writeVec3(buf, anchor.localRef());
                writeVec3(buf, anchor.worldRef());
            }
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
            buf.writeVarInt(p.blockStateId());
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
                buf.writeBoolean(b.armorLike());
            }
            float[] rayStops = spall.rayStops() == null ? new float[0] : spall.rayStops();
            buf.writeVarInt(rayStops.length);
            for (float stop : rayStops) {
                buf.writeFloat(stop);
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
        String entityTypeKey = buf.readUtf(128);
        String summary = buf.readUtf(256);
        Anchor anchor = null;
        if (buf.readBoolean()) {
            String kind = buf.readUtf(16);
            if ("CONTRAPTION".equals(kind)) {
                anchor = Anchor.contraption(buf.readUtf(64));
            } else if ("VS".equals(kind)) {
                anchor = Anchor.vs(readVec3(buf), readVec3(buf));
            }
        }
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
            plates.add(new Plate(readVec3(buf), buf.readUtf(16), buf.readDouble(), buf.readFloat(), buf.readBoolean(),
                buf.readVarInt()));
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
                blocks.add(new SpallBlock(readVec3(buf), buf.readBoolean(), buf.readBoolean()));
            }
            int stopCount = buf.readVarInt();
            float[] rayStops = new float[Math.min(stopCount, 64)];
            for (int i = 0; i < rayStops.length; i++) {
                rayStops[i] = buf.readFloat();
            }
            spall = new Spall(origin, dir, coneCos, range, fragments, blocks, rayStops);
        }
        int entityCount = buf.readVarInt();
        List<EntityHit> entities = new ArrayList<>(Math.min(entityCount, 32));
        for (int i = 0; i < entityCount; i++) {
            entities.add(new EntityHit(buf.readVarInt(), readVec3(buf), buf.readFloat(), buf.readBoolean()));
        }
        return new ClientboundTerminalBallisticsPacket(projectileId, entityTypeKey, summary, anchor, path, plates, spall, entities);
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
