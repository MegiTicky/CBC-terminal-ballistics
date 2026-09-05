package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.client.VSClientCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Client state for the terminal-ballistics hitcam: the x-ray diagram of the
 * last shell-vs-armour engagement. Points arrive in vehicle-local space when
 * the shell hit a vehicle (contraption-local for Create contraptions,
 * shipyard-space for Valkyrien ships), so both the diagram and the orbit
 * camera around it follow the moving vessel.
 */
public final class ClientTerminalView {
    /** Fraction of the view duration spent turning; the rest holds the framing. */
    private static final double SWEEP_END_FRACTION = 0.8D;
    /** Total camera turn over the sweep, radians (~115 degrees). */
    private static final double SWEEP_RADIANS = 2.0D;

    private static ClientboundTerminalBallisticsPacket view;
    private static long startTick;

    // Anchor: null kind = world space, CONTRAPTION = Create entity by UUID,
    // VS = Valkyrien ship identified by localRef (shipyard) + worldRef (world).
    private static String anchorKind;
    private static UUID contraptionUuid;
    private static Entity anchorEntity;
    private static Vec3 vsLocalRef = Vec3.ZERO;
    private static Vec3 vsWorldRef = Vec3.ZERO;
    private static Vec3 vsOffset = Vec3.ZERO;
    private static long shipCacheTick = Long.MIN_VALUE;
    private static boolean shipLookedUp;
    private static Object vsShip;

    private static Vec3 anchorCenter = Vec3.ZERO;
    private static double orbitRadius = 8.0D;

    public static void accept(ClientboundTerminalBallisticsPacket packet) {
        view = packet;
        ClientboundTerminalBallisticsPacket.Anchor anchor = packet.anchor();
        anchorKind = anchor == null ? null : anchor.kind();
        contraptionUuid = null;
        anchorEntity = null;
        vsShip = null;
        shipLookedUp = false;
        shipCacheTick = Long.MIN_VALUE;
        if (anchor != null && "CONTRAPTION".equals(anchor.kind()) && anchor.uuid() != null) {
            contraptionUuid = UUID.fromString(anchor.uuid());
        } else if (anchor != null && "VS".equals(anchor.kind())
            && anchor.localRef() != null && anchor.worldRef() != null) {
            vsLocalRef = anchor.localRef();
            vsWorldRef = anchor.worldRef();
            vsOffset = vsWorldRef.subtract(vsLocalRef);
        }
        Minecraft mc = Minecraft.getInstance();
        startTick = mc.level != null ? mc.level.getGameTime() : 0L;
        recomputeBounds();
        ClientHitcam.startTerminalView();
    }

    public static void clear() {
        view = null;
        anchorEntity = null;
        anchorKind = null;
        contraptionUuid = null;
        vsShip = null;
        shipLookedUp = false;
    }

    public static boolean active() {
        return view != null;
    }

    public static boolean expired() {
        return view == null || ageTicks() >= duration();
    }

    public static int duration() {
        return Math.max(20, TBConfig.HITCAM_TERMINAL_DURATION.get());
    }

    public static String summary() {
        return view == null ? "" : view.summary();
    }

    public static float age(float partialTick) {
        return ageTicks() + partialTick;
    }

    private static long ageTicks() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime() - startTick;
    }

    public static List<HitcamSample> path() {
        return view == null ? List.of() : view.path();
    }

    public static List<ClientboundTerminalBallisticsPacket.Plate> plates() {
        return view == null ? List.of() : view.plates();
    }

    @Nullable
    public static ClientboundTerminalBallisticsPacket.Spall spall() {
        return view == null ? null : view.spall();
    }

    public static List<ClientboundTerminalBallisticsPacket.EntityHit> entities() {
        return view == null ? List.of() : view.entities();
    }

    /** Vehicle-local -> world for any stored point; identity without an anchor. */
    public static Vec3 toWorld(Vec3 local, float partialTick) {
        Entity anchor = contraptionAnchor();
        if (anchor != null) {
            Vec3 world = ContraptionCompat.toGlobalVector(anchor, local, partialTick);
            return world != null ? world : local;
        }
        if ("VS".equals(anchorKind)) {
            Object ship = vsShip();
            Vec3 world = ship != null ? VSClientCompat.shipyardToWorld(ship, local) : null;
            if (world != null) return world;
            // Ship not resolvable right now (unloaded/too far): keep the diagram
            // near the impact point via a static offset instead of vanishing.
            return local.add(vsOffset);
        }
        return local;
    }

    /** Diagram center (orbit target) in world space. */
    public static Vec3 center(float partialTick) {
        return toWorld(anchorCenter, partialTick);
    }

    /**
     * Cinematic camera: a slow, eased turn around the impact that settles into
     * a fixed framing for the tail of the view. Always re-anchored to the
     * vehicle so a moving tank stays framed.
     */
    public static Vec3 cameraPos(float partialTick) {
        Vec3 c = center(partialTick);
        double r = orbitRadius;
        double age = age(partialTick);
        double sweepEnd = Math.max(1.0D, duration() * SWEEP_END_FRACTION);
        double t = Mth.clamp(age / sweepEnd, 0.0D, 1.0D);
        double eased = t * t * (3.0D - 2.0D * t);
        double angle = eased * SWEEP_RADIANS;
        return new Vec3(c.x + Math.cos(angle) * r, c.y + r * 0.5D, c.z + Math.sin(angle) * r);
    }

    /** The Create contraption entity the diagram is anchored to, if any. */
    private static Entity contraptionAnchor() {
        if (!"CONTRAPTION".equals(anchorKind) || contraptionUuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (anchorEntity != null && anchorEntity.isRemoved()) anchorEntity = null;
        if (anchorEntity == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().equals(contraptionUuid)) {
                    anchorEntity = entity;
                    break;
                }
            }
        }
        return anchorEntity;
    }

    /** The live VS ship, resolved at most once per client tick. */
    private static Object vsShip() {
        if (!"VS".equals(anchorKind)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        ClientLevel clientLevel = mc.level;
        long tick = mc.level.getGameTime();
        if (tick != shipCacheTick) {
            shipCacheTick = tick;
            shipLookedUp = false;
            vsShip = null;
        }
        if (!shipLookedUp) {
            shipLookedUp = true;
            vsShip = VSClientCompat.shipForReanchor(clientLevel, vsWorldRef, vsLocalRef);
        }
        return vsShip;
    }

    private static void recomputeBounds() {
        if (view == null) return;
        Vec3 sum = Vec3.ZERO;
        int count = 0;
        double maxDistSq = 0.0D;
        for (ClientboundTerminalBallisticsPacket.Plate plate : view.plates()) {
            sum = sum.add(plate.pos());
            count++;
        }
        if (count == 0 && view.spall() != null) {
            sum = view.spall().origin();
            count = 1;
        }
        anchorCenter = count > 0 ? sum.scale(1.0D / count) : Vec3.ZERO;
        for (ClientboundTerminalBallisticsPacket.Plate plate : view.plates()) {
            maxDistSq = Math.max(maxDistSq, plate.pos().distanceToSqr(anchorCenter));
        }
        if (view.spall() != null) {
            double spallDist = Math.sqrt(view.spall().origin().distanceToSqr(anchorCenter)) + view.spall().range();
            maxDistSq = Math.max(maxDistSq, spallDist * spallDist);
        }
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : view.entities()) {
            maxDistSq = Math.max(maxDistSq, hit.pos().distanceToSqr(anchorCenter));
        }
        orbitRadius = Math.max(5.5D, Math.min(15.0D, Math.sqrt(maxDistSq) + 4.5D));
    }

    private ClientTerminalView() {}
}
