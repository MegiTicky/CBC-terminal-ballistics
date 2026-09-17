package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.network.ClientboundGogglesDebugPacket;
import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client side of the {@code /cbctb goggles debug} overlay. The server sends
 * one packet per detected shell with its audience verdict and where each
 * linked mount actually resolved to; this renders wireframe markers in the
 * world (green = matched/captured, red = unmatched/no audience, yellow =
 * shell removed before its first tick) and a top-left HUD list naming every
 * recent shell's verdict with its link distance. Entries age out after 30 s.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientGogglesDebug {
    private static final long TTL_MILLIS = 30_000L;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_HUD_LINES = 16;

    /** Client copy of the server's per-link diagnostic. */
    public record LinkDiag(BlockPos resolved, double dist, boolean matched, String label) {}

    public static final class Entry {
        final Vec3 pos;
        final String className;
        final String outcome;
        final List<LinkDiag> links;
        final long millis;
        final boolean captured;
        final boolean neverTicked;
        final boolean flyingUnhooked;

        Entry(ClientboundGogglesDebugPacket packet) {
            pos = new Vec3(packet.x(), packet.y(), packet.z());
            className = packet.className();
            outcome = packet.outcome();
            List<LinkDiag> copy = new ArrayList<>(packet.links().size());
            for (ClientboundGogglesDebugPacket.LinkDiag link : packet.links()) {
                copy.add(new LinkDiag(link.resolved(), link.dist(), link.matched(), link.label()));
            }
            links = List.copyOf(copy);
            millis = System.currentTimeMillis();
            captured = outcome.startsWith("CAPTURED");
            neverTicked = outcome.startsWith("NEVER TICKED");
            flyingUnhooked = outcome.startsWith("FLYING");
        }

        /** Nearest resolved link, or null when every link was unresolved. */
        LinkDiag nearestLink() {
            LinkDiag nearest = null;
            for (LinkDiag link : links) {
                if (link.resolved() == null) continue;
                if (nearest == null || link.dist() < nearest.dist()) nearest = link;
            }
            return nearest;
        }
    }

    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private ClientGogglesDebug() {}

    public static void accept(ClientboundGogglesDebugPacket packet) {
        ENTRIES.addLast(new Entry(packet));
        while (ENTRIES.size() > MAX_ENTRIES) ENTRIES.removeFirst();
        prune();
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        while (!ENTRIES.isEmpty() && ENTRIES.peekFirst().millis < cutoff) ENTRIES.removeFirst();
    }

    // ---- world markers ---------------------------------------------------------

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

            float[] verdict = entry.captured ? new float[]{0.2f, 1.0f, 0.4f}
                : entry.flyingUnhooked ? new float[]{1.0f, 0.55f, 0.1f}
                : entry.neverTicked ? new float[]{1.0f, 0.9f, 0.2f}
                : new float[]{1.0f, 0.25f, 0.2f};
            float half = entry.captured ? 0.25f : 0.5f;
            drawBox(poseStack, buf, AABB.ofSize(entry.pos, half * 2, half * 2, half * 2),
                verdict[0], verdict[1], verdict[2], alpha);

            LinkDiag nearest = null;
            for (LinkDiag link : entry.links) {
                if (link.resolved() == null) continue;
                if (nearest == null || link.dist() < nearest.dist()) nearest = link;
                float[] linkColor = link.matched ? new float[]{0.2f, 1.0f, 0.4f}
                    : new float[]{1.0f, 0.25f, 0.2f};
                drawBox(poseStack, buf, new AABB(link.resolved()),
                    linkColor[0], linkColor[1], linkColor[2], alpha * 0.9f);
            }
            // The mismatch is the interesting case: tie the rejected shell to
            // where its nearest link actually resolved.
            if (!entry.captured && nearest != null) {
                drawLine(poseStack, buf, entry.pos, Vec3.atCenterOf(nearest.resolved()),
                    1.0f, 1.0f, 1.0f, alpha * 0.6f);
            }
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    // ---- HUD list ---------------------------------------------------------------

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

        gui.drawString(font, Component.literal("CBCTB goggles debug (30s):")
            .withStyle(ChatFormatting.WHITE), x, y, 0xFFFFFF);
        y += 10;

        // Newest entry with links shows where the mounts actually resolve now.
        Entry newestWithLinks = null;
        for (Iterator<Entry> it = ENTRIES.descendingIterator(); it.hasNext(); ) {
            Entry entry = it.next();
            if (!entry.links.isEmpty()) {
                newestWithLinks = entry;
                break;
            }
        }
        if (newestWithLinks != null) {
            for (LinkDiag link : newestWithLinks.links) {
                String where = link.resolved() != null
                    ? "(" + link.resolved().getX() + ", " + link.resolved().getY() + ", " + link.resolved().getZ() + ")"
                    : "UNRESOLVED";
                MutableComponent line = Component.literal("  " + link.label() + " @ " + where)
                    .withStyle(link.matched ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED);
                gui.drawString(font, line, x, y, 0xFFFFFF);
                y += 10;
            }
        }

        int shown = 0;
        for (Iterator<Entry> it = ENTRIES.descendingIterator(); it.hasNext() && shown < MAX_HUD_LINES; ) {
            Entry entry = it.next();
            shown++;
            int age = (int) ((now - entry.millis) / 1000L);
            StringBuilder line = new StringBuilder("[-").append(age).append("s] ")
                .append(entry.className).append(" (")
                .append((int) Math.floor(entry.pos.x)).append(' ')
                .append((int) Math.floor(entry.pos.y)).append(' ')
                .append((int) Math.floor(entry.pos.z)).append(") ");
            ChatFormatting verdictColor = entry.captured ? ChatFormatting.GREEN
                : entry.flyingUnhooked ? ChatFormatting.GOLD
                : entry.neverTicked ? ChatFormatting.YELLOW : ChatFormatting.RED;
            MutableComponent text = Component.literal(line.toString()).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(entry.outcome).withStyle(verdictColor));
            LinkDiag nearest = entry.nearestLink();
            if (!entry.captured && nearest != null) {
                text.append(Component.literal(" d=" + (int) nearest.dist()).withStyle(ChatFormatting.GRAY));
            }
            gui.drawString(font, text, x, y, 0xFFFFFF);
            y += 10;
        }
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer buf, Vec3 from, Vec3 to,
                                 float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        buf.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
        buf.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, a).normal(pose.normal(), 0, 1, 0).endVertex();
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer buf, AABB box,
                                float r, float g, float b, float a) {
        Vec3 min = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);
        // Bottom face
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(max.x, min.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(min.x, min.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, min.y, min.z), r, g, b, a);
        // Top face
        drawLine(poseStack, buf, new Vec3(min.x, max.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, min.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, max.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, max.y, max.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        // Vertical edges
        drawLine(poseStack, buf, new Vec3(min.x, min.y, min.z), new Vec3(min.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, min.z), new Vec3(max.x, max.y, min.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(max.x, min.y, max.z), new Vec3(max.x, max.y, max.z), r, g, b, a);
        drawLine(poseStack, buf, new Vec3(min.x, min.y, max.z), new Vec3(min.x, max.y, max.z), r, g, b, a);
    }
}
