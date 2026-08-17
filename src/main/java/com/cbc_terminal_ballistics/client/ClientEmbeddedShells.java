package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.state.EmbeddedShell;
import com.cbc_terminal_ballistics.util.CBCReflect;
import com.cbc_terminal_ballistics.util.SableCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.client.renderer.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.joml.Matrix4f;
import org.joml.Vector3f;

@EventBusSubscriber(modid = "cbc_terminal_ballistics", value = Dist.CLIENT)
public final class ClientEmbeddedShells {
    private static final Map<BlockPos, ShellSet> SHELLS = new HashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("cbc_terminal_ballistics:ClientEmbeddedShells");
    private static volatile boolean debugEnabled;
    private static int debugLogCounter;

    public static void accept(BlockPos pos, UUID subLevelId, List<EmbeddedShell> shells) {
        if (shells.isEmpty()) SHELLS.remove(pos);
        else SHELLS.put(pos.immutable(), new ShellSet(subLevelId, List.copyOf(shells)));
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        debugLogCounter = 0;
        LOGGER.warn("Embedded shell debug overlay {}", enabled ? "enabled" : "disabled");
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            SHELLS.clear();
            return;
        }
        SHELLS.entrySet().removeIf(entry -> {
            UUID current = SableCompat.subLevelId(mc.level, entry.getKey());
            ShellSet set = entry.getValue();
            // A null lookup is transient while Sable initializes or streams a ship.
            // Discard the entry only when this plot is positively owned by another ship.
            return current != null && !Objects.equals(set.subLevelId(), current)
                || set.shells().stream().allMatch(shell -> shell.visualState() == null && (shell.visualItem() == null || shell.visualItem().isEmpty()));
        });
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || SHELLS.isEmpty()) return;

        Vec3 camera = event.getCamera().getPosition();
        List<ShellRender> candidates = new ArrayList<>();
        for (Map.Entry<BlockPos, ShellSet> entry : SHELLS.entrySet()) {
            BlockPos pos = entry.getKey();
            ShellSet set = entry.getValue();
            if (!hasCurrentOwner(mc.level, pos, set)) continue;
            if (SableCompat.squaredDistanceBetweenInclSubLevels(mc.level, Vec3.atCenterOf(pos), mc.player.position()) > 128 * 128) continue;
            for (EmbeddedShell shell : set.shells()) {
                if (shell.visualState() != null || shell.visualItem() != null && !shell.visualItem().isEmpty()) candidates.add(new ShellRender(pos, set, shell));
            }
        }
        candidates.sort(Comparator.comparingLong((ShellRender render) -> render.shell().gameTime()).reversed());
        int limit = maxRenderedShells();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (int i = 0; i < Math.min(limit, candidates.size()); i++) renderShell(mc.level, candidates.get(i), event.getPoseStack(), camera, buffers);
        buffers.endBatch();
    }

    private static int maxRenderedShells() {
        try {
            return Math.max(0, TBConfig.MAX_RENDERED_EMBEDDED_SHELLS.get());
        } catch (IllegalStateException ignored) {
            return 256;
        }
    }

    private static boolean hasCurrentOwner(Level level, BlockPos pos, ShellSet set) {
        UUID current = SableCompat.subLevelId(level, pos);
        if (set.subLevelId() != null) return current == null || set.subLevelId().equals(current);
        return current == null && !SableCompat.isProbablyInSubLevel(pos);
    }

    private static void renderShell(Level level, ShellRender render, PoseStack poseStack, Vec3 camera, MultiBufferSource.BufferSource buffers) {
        BlockPos pos = render.pos();
        EmbeddedShell shell = render.shell();
        poseStack.pushPose();
        Vec3 direction = new Vec3(shell.directionX(), shell.directionY(), shell.directionZ());
        if (direction.lengthSqr() < 1.0e-8D) direction = Vec3.atLowerCornerOf(shell.face().getNormal()).scale(-1.0D);
        direction = direction.normalize();
        double offset = shell.depth() - 0.5D;
        Vec3 localPosition = new Vec3(
            pos.getX() + (double) shell.x() + direction.x * offset,
            pos.getY() + (double) shell.y() + direction.y * offset,
            pos.getZ() + (double) shell.z() + direction.z * offset);
        boolean sableSubLevelShell = render.set().subLevelId() != null || (SableCompat.isPresent()
            && (SableCompat.isProbablyInSubLevel(pos) || SableCompat.isInSubLevel(level, pos)));
        SableClientCompat.RenderTransform transform = sableSubLevelShell && level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel
            ? SableClientCompat.renderTransformWithSubLevel(clientLevel, pos, camera) : null;
        if (sableSubLevelShell) {
            // Never render a ship-local point as a main-world position. Sable can
            // temporarily lack a render pose while a ship is loading or unloading.
            if (transform == null) {
                poseStack.popPose();
                return;
            }
            Vec3 renderPosition = transform.positionOrNull(localPosition);
            if (renderPosition == null) {
                poseStack.popPose();
                return;
            }
            renderDebug(level, render, camera, transform, renderPosition, localPosition, direction, poseStack, buffers);
            // Match Sable's block-decal transform: position the complete local point,
            // then preserve the ship's full model basis before CBC's local shell pose.
            poseStack.translate(renderPosition.x, renderPosition.y, renderPosition.z);
            poseStack.mulPose(transform.orientation());
            Vector3f scale = transform.scale();
            poseStack.scale(scale.x, scale.y, scale.z);
        } else {
            Vec3 renderPosition = localPosition.subtract(camera);
            if (debugEnabled) renderDebugNormal(render, camera, renderPosition, localPosition, direction, poseStack, buffers);
            poseStack.translate(renderPosition.x, renderPosition.y, renderPosition.z);
        }
        BlockState state = shell.visualState();
        ItemStack item = shell.visualItem();
        int light = LightTexture.FULL_BRIGHT;
        if (state != null) {
            // Match CBC's BigCannonProjectileRenderer: projectile block models are
            // full-scale and oriented along the projectile's flight direction.
            if (direction.horizontalDistanceSqr() > 1.0e-4D && Math.abs(direction.y) > 0.01D) {
                Vec3 horizontal = new Vec3(direction.x, 0.0D, direction.z).normalize();
                poseStack.mulPose(new Matrix4f(CBCReflect.facing(direction.reverse(), horizontal)));
                poseStack.mulPose(new Matrix4f(CBCReflect.facing(horizontal)));
            } else {
                poseStack.mulPose(new Matrix4f(CBCReflect.facing(direction)));
            }
            poseStack.translate(-0.5D, -0.5D, -0.5D);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, buffers, light, OverlayTexture.NO_OVERLAY);
        } else if (item != null && !item.isEmpty()) {
            // Legacy/non-CBC projectiles may not expose a rendered block state.
            poseStack.scale(0.22F, 0.22F, 0.22F);
            Minecraft.getInstance().getItemRenderer().renderStatic(item, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, poseStack, buffers, level, 0);
        }
        poseStack.popPose();
    }

    private static void renderDebug(Level level, ShellRender render, Vec3 camera,
                                    SableClientCompat.RenderTransform transform, Vec3 renderPosition,
                                    Vec3 localPosition, Vec3 direction, PoseStack poseStack,
                                    MultiBufferSource.BufferSource buffers) {
        if (!debugEnabled) return;
        Vec3 shellAnchor = new Vec3(render.pos().getX() + (double) render.shell().x(),
            render.pos().getY() + (double) render.shell().y(), render.pos().getZ() + (double) render.shell().z());
        Vec3 markAnchor = ClientImpactMarks.debugAnchorNear(render.pos(), shellAnchor);
        Vec3 renderedShellAnchor = transform.position(shellAnchor);
        Vec3 renderedMarkAnchor = markAnchor == null ? null : transform.position(markAnchor);
        Vec3 renderedDirectionTip = transform.position(localPosition.add(direction));
        VertexConsumer buffer = buffers.getBuffer(RenderType.lightning());
        Matrix4f matrix = poseStack.last().pose();
        if (renderedMarkAnchor != null) drawCross(buffer, matrix, renderedMarkAnchor, 0.07D, 0.2F, 1.0F, 0.2F, 1.0F);
        drawCross(buffer, matrix, renderedShellAnchor, 0.07D, 1.0F, 0.2F, 0.2F, 1.0F);
        drawCross(buffer, matrix, renderPosition, 0.09D, 1.0F, 0.85F, 0.1F, 1.0F);
        drawLine(buffer, matrix, renderedShellAnchor, renderPosition, 1.0F, 0.85F, 0.1F, 1.0F);
        drawLine(buffer, matrix, renderPosition, renderedDirectionTip, 0.1F, 0.9F, 1.0F, 1.0F);
        logDebug(render, markAnchor, shellAnchor, localPosition, direction, renderPosition, renderedMarkAnchor, camera);
    }

    private static void renderDebugNormal(ShellRender render, Vec3 camera, Vec3 renderPosition,
                                           Vec3 localPosition, Vec3 direction, PoseStack poseStack,
                                           MultiBufferSource.BufferSource buffers) {
        Vec3 shellAnchor = new Vec3(render.pos().getX() + (double) render.shell().x(),
            render.pos().getY() + (double) render.shell().y(), render.pos().getZ() + (double) render.shell().z());
        Vec3 markAnchor = ClientImpactMarks.debugAnchorNear(render.pos(), shellAnchor);
        Vec3 renderedShellAnchor = shellAnchor.subtract(camera);
        Vec3 renderedMarkAnchor = markAnchor == null ? null : markAnchor.subtract(camera);
        Vec3 renderedDirectionTip = localPosition.add(direction).subtract(camera);
        VertexConsumer buffer = buffers.getBuffer(RenderType.lightning());
        Matrix4f matrix = poseStack.last().pose();
        if (renderedMarkAnchor != null) drawCross(buffer, matrix, renderedMarkAnchor, 0.07D, 0.2F, 1.0F, 0.2F, 1.0F);
        drawCross(buffer, matrix, renderedShellAnchor, 0.07D, 1.0F, 0.2F, 0.2F, 1.0F);
        drawCross(buffer, matrix, renderPosition, 0.09D, 1.0F, 0.85F, 0.1F, 1.0F);
        drawLine(buffer, matrix, renderedShellAnchor, renderPosition, 1.0F, 0.85F, 0.1F, 1.0F);
        drawLine(buffer, matrix, renderPosition, renderedDirectionTip, 0.1F, 0.9F, 1.0F, 1.0F);
        logDebug(render, markAnchor, shellAnchor, localPosition, direction, renderPosition, renderedMarkAnchor, camera);
    }

    private static void logDebug(ShellRender render, Vec3 markAnchor, Vec3 shellAnchor, Vec3 localPosition,
                                 Vec3 direction, Vec3 renderPosition, Vec3 renderedMarkAnchor, Vec3 camera) {
        if (debugLogCounter++ >= 8) return;
        EmbeddedShell shell = render.shell();
        double offset = shell.depth() - 0.5D;
        LOGGER.warn("shellDebug #{} pos={} subLevel={} markLocal={} shellLocal={} depthLocal={} direction={} depth={} offset={} renderCenter={} renderMark={} camera={} markToShell={}",
            debugLogCounter, render.pos().toShortString(), render.set().subLevelId(), markAnchor, shellAnchor,
            localPosition, direction, shell.depth(), offset, renderPosition, renderedMarkAnchor, camera,
            markAnchor == null ? "missing" : markAnchor.subtract(shellAnchor));
    }

    private static void drawCross(VertexConsumer buffer, Matrix4f matrix, Vec3 center, double size,
                                  float red, float green, float blue, float alpha) {
        drawLine(buffer, matrix, center.add(-size, 0.0D, 0.0D), center.add(size, 0.0D, 0.0D), red, green, blue, alpha);
        drawLine(buffer, matrix, center.add(0.0D, -size, 0.0D), center.add(0.0D, size, 0.0D), red, green, blue, alpha);
        drawLine(buffer, matrix, center.add(0.0D, 0.0D, -size), center.add(0.0D, 0.0D, size), red, green, blue, alpha);
    }

    private static void drawLine(VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end,
                                 float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, (float) start.x, (float) start.y, (float) start.z)
            .setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, (float) end.x, (float) end.y, (float) end.z)
            .setColor(red, green, blue, alpha);
    }

    private record ShellSet(UUID subLevelId, List<EmbeddedShell> shells) {}
    private record ShellRender(BlockPos pos, ShellSet set, EmbeddedShell shell) {}
    private ClientEmbeddedShells() {}
}
