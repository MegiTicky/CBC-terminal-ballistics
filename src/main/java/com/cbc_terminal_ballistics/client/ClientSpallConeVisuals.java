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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@EventBusSubscriber(modid = "cbc_terminal_ballistics", value = Dist.CLIENT)
public final class ClientSpallConeVisuals {
    private static final int MAX_ACTIVE_STREAKS = 96;
    private static final List<Streak> STREAKS = new ArrayList<>();

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
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            STREAKS.clear();
            return;
        }

        long now = mc.level.getGameTime();
        STREAKS.removeIf(streak -> now - streak.startTick() >= streak.lifetimeTicks());
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || STREAKS.isEmpty()) return;

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
            renderStreak(buffer, matrix, streak, camera, age + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true));
        }

        buffers.endBatch(RenderType.lightning());
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
        buffer.addVertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
            .setColor(red, green, blue, alpha);
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

    private ClientSpallConeVisuals() {}
}
