package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Client state for the terminal-ballistics hitcam: the x-ray diagram of the
 * last shell-vs-armour engagement. Points arrive in contraption-local space
 * when the shell hit a vehicle, so both the diagram and the orbit camera
 * around it follow the moving ship.
 */
public final class ClientTerminalView {
    private static ClientboundTerminalBallisticsPacket view;
    private static long startTick;
    private static UUID anchorUuid;
    private static Entity anchorEntity;
    private static Vec3 anchorCenter = Vec3.ZERO;
    private static double orbitRadius = 8.0D;

    public static void accept(ClientboundTerminalBallisticsPacket packet) {
        view = packet;
        anchorUuid = packet.anchorUuid() != null ? UUID.fromString(packet.anchorUuid()) : null;
        anchorEntity = null;
        Minecraft mc = Minecraft.getInstance();
        startTick = mc.level != null ? mc.level.getGameTime() : 0L;
        recomputeBounds();
        ClientHitcam.startTerminalView();
    }

    public static void clear() {
        view = null;
        anchorEntity = null;
        anchorUuid = null;
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

    /** Contraption-local -> world for any stored point; identity without an anchor. */
    public static Vec3 toWorld(Vec3 local, float partialTick) {
        Entity anchor = anchor();
        if (anchor == null) return local;
        Vec3 world = ContraptionCompat.toGlobalVector(anchor, local, partialTick);
        return world != null ? world : local;
    }

    /** Diagram center (orbit target) in world space. */
    public static Vec3 center(float partialTick) {
        return toWorld(anchorCenter, partialTick);
    }

    /**
     * Cinematic orbit camera: circles the impact area at a fixed radius, always
     * re-anchored to the vehicle so a moving tank stays framed.
     */
    public static Vec3 cameraPos(float partialTick) {
        Vec3 c = center(partialTick);
        double angle = age(partialTick) * 0.045D;
        double r = orbitRadius;
        return new Vec3(c.x + Math.cos(angle) * r, c.y + r * 0.5D, c.z + Math.sin(angle) * r);
    }

    private static Entity anchor() {
        if (anchorUuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (anchorEntity != null && anchorEntity.isRemoved()) anchorEntity = null;
        if (anchorEntity == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().equals(anchorUuid)) {
                    anchorEntity = entity;
                    break;
                }
            }
        }
        return anchorEntity;
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
