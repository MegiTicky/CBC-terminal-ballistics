package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.network.ClientboundShellHitDebugPacket;
import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Client side of the {@code /cbctb shelldebug} overlay. Each packet is one
 * shell's contraption-decision verdict: world marker at the shell position
 * (green = processed outcome, orange = skipped, red = missed, yellow = jammed)
 * and a top-left HUD list naming the reason the shell did or didn't hit.
 * Entries age out after 30 s.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientShellHitDebug {
    private static final long TTL_MILLIS = 30_000L;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_HUD_LINES = 16;

    public static final class Entry {
        final Vec3 pos;
        final String shell;
        final String verdict;
        final String detail;
        final long millis;

        Entry(ClientboundShellHitDebugPacket packet) {
            pos = new Vec3(packet.x(), packet.y(), packet.z());
            shell = packet.shell();
            verdict = packet.verdict();
            detail = packet.detail();
            millis = System.currentTimeMillis();
        }

        boolean is(String prefix) {
            return verdict.startsWith(prefix);
        }

        ChatFormatting color() {
            if (is("OUTCOME")) return ChatFormatting.GREEN;
            if (is("SKIP")) return ChatFormatting.GOLD;
            if (is("JAM")) return ChatFormatting.YELLOW;
            return ChatFormatting.RED;
        }

        float[] markerColor() {
            if (is("OUTCOME")) return new float[]{0.2f, 1.0f, 0.4f};
            if (is("SKIP")) return new float[]{1.0f, 0.55f, 0.1f};
            if (is("JAM")) return new float[]{1.0f, 0.9f, 0.2f};
            return new float[]{1.0f, 0.25f, 0.2f};
        }
    }

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private ClientShellHitDebug() {}

    public static void accept(ClientboundShellHitDebugPacket packet) {
        ENTRIES.addLast(new Entry(packet));
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        prune();
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        while (!ENTRIES.isEmpty() && ENTRIES.peekFirst().millis < cutoff) ENTRIES.removeFirst();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (ClientHitcamPiP.isRenderingPip()) return;
        if (ENTRIES.isEmpty()) return;
        prune();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer buf = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        long now = System.currentTimeMillis();
        for (Entry entry : ENTRIES) {
            float age = (now - entry.millis) / 1000f;
            float alpha = age > 25f ? (30f - age) / 5f : 1.0f;
            if (alpha <= 0f) continue;
            float[] color = entry.markerColor();
            float half = entry.is("OUTCOME") ? 0.25f : 0.45f;
            drawBox(poseStack, buf, AABB.ofSize(entry.pos, half * 2, half * 2, half * 2),
                color[0], color[1], color[2], alpha);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onHudOverlay(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (ENTRIES.isEmpty()) return;
        prune();

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics gui = event.getGuiGraphics();
        Font font = mc.font;
        int x = 4;
        int y = 4;
        long now = System.currentTimeMillis();

        gui.drawString(font, Component.literal("CBCTB shell debug (30s):")
            .withStyle(ChatFormatting.WHITE), x, y, 0xFFFFFF);
        y += 10;

        int shown = 0;
        for (Iterator<Entry> it = ENTRIES.descendingIterator(); it.hasNext() && shown < MAX_HUD_LINES; ) {
            Entry entry = it.next();
            shown++;
            int age = (int) ((now - entry.millis) / 1000L);
            MutableComponent line = Component.literal(String.format("[-%ds] %s (%d %d %d) ", age, entry.shell,
                (int) Math.floor(entry.pos.x), (int) Math.floor(entry.pos.y), (int) Math.floor(entry.pos.z)))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(entry.verdict).withStyle(entry.color()));
            gui.drawString(font, line, x, y, 0xFFFFFF);
            y += 10;
            if (!entry.detail.isEmpty()) {
                gui.drawString(font, Component.literal("    " + entry.detail).withStyle(ChatFormatting.DARK_GRAY), x, y, 0xFFFFFF);
                y += 10;
            }
        }
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer buf, AABB box,
                                float r, float g, float b, float a) {
        Vec3 min = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer buf, Vec3 from, Vec3 to,
                                 float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        buf.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
        buf.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
    }
}
