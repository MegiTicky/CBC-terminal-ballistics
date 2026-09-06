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
                                                  List<Spall> spalls, List<EntityHit> entities) {

    /**
     * {@code blockStateId} is the struck block's state id ({@code Block.getId});
     * the client renders the plate highlight as that block's real shape. A
     * negative id means unknown (full-cube fallback).
     *
     * <p>{@code shapeBoxes} holds the block's voxel-shape boxes captured
     * server-side at the impact site (each {@code [minX, minY, minZ, maxX,
     * maxY, maxZ]}, block-relative 0..1). Copycat/framed blocks derive their
     * shape from their block entity, which the client cannot always see, so
     * the shape travels with the plate; empty means not captured and the
     * client falls back to decoding it from the state id.</p>
     */
    public record Plate(Vec3 pos, String outcome, double damage, double toughness, boolean broken,
                        int blockStateId, float[][] shapeBoxes) {}

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
            float[][] boxes = p.shapeBoxes();
            buf.writeVarInt(boxes == null ? 0 : Math.min(boxes.length, 8));
            if (boxes != null) {
                for (int i = 0; i < boxes.length && i < 8; i++) {
                    float[] box = boxes[i];
                    for (float v : box) {
                        buf.writeFloat(v);
                    }
                }
            }
        }
        buf.writeVarInt(spalls == null ? 0 : spalls.size());
        for (Spall spall : spalls == null ? List.<Spall>of() : spalls) {
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
            Vec3 pos = readVec3(buf);
            String outcome = buf.readUtf(16);
            double damage = buf.readDouble();
            float toughness = buf.readFloat();
            boolean broken = buf.readBoolean();
            int stateId = buf.readVarInt();
            int boxCount = buf.readVarInt();
            float[][] boxes = boxCount <= 0 ? new float[0][] : new float[Math.min(boxCount, 8)][];
            for (int b = 0; b < boxes.length; b++) {
                boxes[b] = new float[]{buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()};
            }
            plates.add(new Plate(pos, outcome, damage, toughness, broken, stateId, boxes));
        }
        int spallCount = buf.readVarInt();
        List<Spall> spalls = new ArrayList<>(Math.min(spallCount, 8));
        for (int c = 0; c < spallCount; c++) {
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
            spalls.add(new Spall(origin, dir, coneCos, range, fragments, blocks, rayStops));
        }
        int entityCount = buf.readVarInt();
        List<EntityHit> entities = new ArrayList<>(Math.min(entityCount, 32));
        for (int i = 0; i < entityCount; i++) {
            entities.add(new EntityHit(buf.readVarInt(), readVec3(buf), buf.readFloat(), buf.readBoolean()));
        }
        return new ClientboundTerminalBallisticsPacket(projectileId, entityTypeKey, summary, anchor, path, plates, spalls, entities);
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
