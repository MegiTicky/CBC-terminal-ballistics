package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.cbc_terminal_ballistics.network.ClientboundSpallConePacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientSpallConeVisuals {
    private static final int MAX_ACTIVE_STREAKS = 96;
    private static final List<Streak> STREAKS = new ArrayList<>();
    private static final int MAX_ACTIVE_PATHS = 8;
    private static final List<PathRender> PATHS = new ArrayList<>();
    private static final int PATH_GROW_TICKS = 10;
    private static final int PATH_FADE_TICKS = 40;

    /** Goggle-triggered replay of a stored spall cone's display polylines. */
    public static void acceptPath(float[] points, int[] rayPointCounts, int renderTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || points == null || rayPointCounts == null
            || renderTicks <= 0 || points.length < 6 || rayPointCounts.length == 0) return;
        PATHS.add(new PathRender(points, rayPointCounts, minecraft.level.getGameTime(), renderTicks));
        int overflow = PATHS.size() - MAX_ACTIVE_PATHS;
        if (overflow > 0) PATHS.subList(0, overflow).clear();
    }

    public static void accept(ClientboundSpallConePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        Vec3 origin = packet.origin();
        Vec3 forward = packet.forward();
        if (forward.lengthSqr() < 1.0e-6D) return;

        int rays = Mth.clamp(packet.visualFragments(), 0, 24);
        if (rays <= 0) return;

        double coneCos = Mth.clamp(packet.coneCos(), 0.05D, 0.999D);
        double range = Mth.clamp(packet.range(), 1.0D, 24.0D);
        float intensity = Mth.clamp(packet.intensity(), 0.35F, 2.5F);
        TBCaliber caliber = packet.caliber();
        RandomSource random = RandomSource.create(packet.seed());
        long now = minecraft.level.getGameTime();

        for (int i = 0; i < rays; i++) {
            Vec3 ray = fragmentDirection(forward, i, rays, coneCos, random.nextDouble());
            double startDistance = 0.12D + random.nextDouble() * 0.45D;
            double travelDistance = (2.4D + random.nextDouble() * 3.6D + caliber.ordinal() * 0.65D) * (0.85D + intensity * 0.18D);
            double maxDistance = Math.min(range, startDistance + Mth.clamp(travelDistance, 1.75D, 8.0D));
            if (maxDistance <= startDistance + 0.35D) continue;

            float width = (float) Mth.clamp(0.030D + caliber.ordinal() * 0.006D + intensity * 0.010D + random.nextDouble() * 0.012D, 0.025D, 0.085D);
            int lifetime = Mth.clamp(6 + caliber.ordinal() / 2 + random.nextInt(4), 6, 10);
            double segmentLength = Mth.clamp(0.45D + random.nextDouble() * 0.45D + caliber.ordinal() * 0.08D + intensity * 0.10D, 0.45D, 1.40D);
            double speed = (maxDistance - startDistance + segmentLength) / Math.max(1.0D, lifetime - 1.0D);
            speed *= 0.88D + random.nextDouble() * 0.24D;
            STREAKS.add(new Streak(origin, ray, startDistance, maxDistance, speed, segmentLength, width, now, lifetime, intensity));
        }
        trimActiveStreaks();
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            STREAKS.clear();
            return;
        }

        long now = mc.level.getGameTime();
        STREAKS.removeIf(streak -> now - streak.startTick() >= streak.lifetimeTicks());
        PATHS.removeIf(path -> now - path.startTick() >= path.renderTicks());
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP.isRenderingPip()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || (STREAKS.isEmpty() && PATHS.isEmpty())) return;

        long now = mc.level.getGameTime();
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer buffer = buffers.getBuffer(RenderType.lightning());

        Iterator<Streak> iterator = STREAKS.iterator();
        while (iterator.hasNext()) {
            Streak streak = iterator.next();
            long age = now - streak.startTick();
            if (age < 0 || age >= streak.lifetimeTicks()) {
                iterator.remove();
                continue;
            }
            renderStreak(buffer, matrix, streak, camera, age + event.getPartialTick());
        }

        Iterator<PathRender> pathIterator = PATHS.iterator();
        while (pathIterator.hasNext()) {
            PathRender path = pathIterator.next();
            long age = now - path.startTick();
            if (age < 0 || age >= path.renderTicks()) {
                pathIterator.remove();
                continue;
            }
            renderPath(buffer, matrix, path, camera, age + event.getPartialTick());
        }

        buffers.endBatch(RenderType.lightning());
    }

    /** Grows each ray along its polyline from the origin, then holds and fades. */
    private static void renderPath(VertexConsumer buffer, Matrix4f matrix, PathRender path, Vec3 camera, float age) {
        float grow = Mth.clamp(age / PATH_GROW_TICKS, 0.0F, 1.0F);
        float fade = Mth.clamp((path.renderTicks() - age) / PATH_FADE_TICKS, 0.0F, 1.0F);
        if (fade <= 0.01F) return;

        int[] counts = path.rayPointCounts();
        float[] points = path.points();
        int maxPoints = points.length / 3;
        for (int i = 0, offset = 0; i < counts.length; offset += counts[i], i++) {
            if (counts[i] < 2 || offset + counts[i] > maxPoints) continue;
            double totalLen = 0.0D;
            for (int p = 1; p < counts[i]; p++) {
                totalLen += polylinePoint(points, offset + p).distanceTo(polylinePoint(points, offset + p - 1));
            }
            double allowed = totalLen * grow;
            Vec3 prev = polylinePoint(points, offset);
            for (int p = 1; p < counts[i] && allowed > 0.02D; p++) {
                Vec3 next = polylinePoint(points, offset + p);
                Vec3 seg = next.subtract(prev);
                double segLen = seg.length();
                if (segLen < 1.0e-6D) { prev = next; continue; }
                double drawLen = Math.min(segLen, allowed);
                Vec3 end = prev.add(seg.scale(drawLen / segLen));
                renderPathSegment(buffer, matrix, prev, end, camera, fade);
                allowed -= segLen;
                prev = next;
            }
        }
    }

    private static void renderPathSegment(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end, Vec3 camera, float alpha) {
        Vec3 dir = end.subtract(start);
        if (dir.lengthSqr() < 1.0e-8D) return;
        dir = dir.normalize();
        Vec3 mid = start.add(end).scale(0.5D);
        Vec3 toCamera = camera.subtract(mid);
        Vec3 side = dir.cross(toCamera);
        if (side.lengthSqr() < 1.0e-6D) {
            Vec3 fallback = Math.abs(dir.y) < 0.92D ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            side = dir.cross(fallback);
        }
        if (side.lengthSqr() < 1.0e-6D) return;
        side = side.normalize();

        // Same outer-orange / yellow-core pair as the live spall streaks.
        quad(buffer, matrix, start, end, side, camera, 0.070D, 1.0F, 0.48F, 0.05F, alpha * 0.36F);
        quad(buffer, matrix, start, end, side, camera, 0.038D, 1.0F, 0.94F, 0.18F, alpha * 0.9F);
    }

    private static Vec3 polylinePoint(float[] points, int index) {
        return new Vec3(points[index * 3], points[index * 3 + 1], points[index * 3 + 2]);
    }

    private static void renderStreak(VertexConsumer buffer, Matrix4f matrix, Streak streak, Vec3 camera, float age) {
        float progress = age / Math.max(1.0F, streak.lifetimeTicks());
        float alpha = (1.0F - progress) * Mth.clamp(0.70F + streak.intensity() * 0.15F, 0.65F, 0.95F);
        if (alpha <= 0.01F) return;

        Vec3 dir = streak.direction();
        if (dir.lengthSqr() < 1.0e-6D) return;
        double headDistance = streak.startDistance() + streak.speed() * age;
        double tailDistance = headDistance - streak.segmentLength();
        headDistance = Mth.clamp(headDistance, streak.startDistance(), streak.maxDistance());
        tailDistance = Mth.clamp(tailDistance, streak.startDistance(), streak.maxDistance());
        if (headDistance <= tailDistance + 0.04D) return;

        Vec3 start = streak.origin().add(dir.scale(tailDistance));
        Vec3 end = streak.origin().add(dir.scale(headDistance));

        Vec3 mid = start.add(end).scale(0.5D);
        Vec3 toCamera = camera.subtract(mid);
        Vec3 side = dir.cross(toCamera);
        if (side.lengthSqr() < 1.0e-6D) {
            Vec3 fallback = Math.abs(dir.y) < 0.92D ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            side = dir.cross(fallback);
        }
        if (side.lengthSqr() < 1.0e-6D) return;
        side = side.normalize();

        // Outer orange glow plus a narrower yellow core gives a readable long strip without any texture asset.
        quad(buffer, matrix, start, end, side, camera, streak.width() * 1.85D, 1.0F, 0.48F, 0.05F, alpha * 0.36F);
        quad(buffer, matrix, start, end, side, camera, streak.width(), 1.0F, 0.94F, 0.18F, alpha);
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end, Vec3 side, Vec3 camera,
                             double halfWidth, float red, float green, float blue, float alpha) {
        Vec3 offset = side.scale(halfWidth);
        Vec3 s0 = start.subtract(offset).subtract(camera);
        Vec3 s1 = start.add(offset).subtract(camera);
        Vec3 e1 = end.add(offset.scale(0.45D)).subtract(camera);
        Vec3 e0 = end.subtract(offset.scale(0.45D)).subtract(camera);
        vertex(buffer, matrix, s0, red, green, blue, alpha);
        vertex(buffer, matrix, s1, red, green, blue, alpha);
        vertex(buffer, matrix, e1, red, green, blue, 0.0F);
        vertex(buffer, matrix, e0, red, green, blue, 0.0F);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3 pos, float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
            .color(red, green, blue, alpha)
            .endVertex();
    }

    private static void trimActiveStreaks() {
        int overflow = STREAKS.size() - MAX_ACTIVE_STREAKS;
        if (overflow <= 0) return;
        STREAKS.subList(0, overflow).clear();
    }

    private static Vec3 fragmentDirection(Vec3 forward, int index, int count, double coneCos, double jitter) {
        Vec3 f = forward.normalize();
        Vec3 up = Math.abs(f.y) < 0.92D ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 right = f.cross(up).normalize();
        Vec3 orthoUp = right.cross(f).normalize();
        double golden = Math.PI * (3.0D - Math.sqrt(5.0D));
        double t = (index + 0.5D) / Math.max(1, count);
        double cos = 1.0D - (1.0D - coneCos) * t * t;
        double sin = Math.sqrt(Math.max(0.0D, 1.0D - cos * cos));
        double phi = index * golden + jitter * 0.35D;
        return f.scale(cos).add(right.scale(Math.cos(phi) * sin)).add(orthoUp.scale(Math.sin(phi) * sin)).normalize();
    }

    private record Streak(Vec3 origin, Vec3 direction, double startDistance, double maxDistance, double speed,
                          double segmentLength, float width, long startTick, int lifetimeTicks, float intensity) {}

    private record PathRender(float[] points, int[] rayPointCounts, long startTick, int renderTicks) {}

    private ClientSpallConeVisuals() {}
}
