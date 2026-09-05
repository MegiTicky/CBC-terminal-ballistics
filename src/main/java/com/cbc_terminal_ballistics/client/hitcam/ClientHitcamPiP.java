package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Renders the hitcam replay into a private framebuffer once per frame by
 * calling the vanilla level renderer with a temporary camera (the nested-pass
 * technique other camera mods use on 1.20.1), then blits that framebuffer as
 * a small picture-in-picture window over the HUD.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHitcamPiP {
    /** Guards RenderLevelStageEvent subscribers (ours and, via {@link #isRenderingPip()}, the mod's other visuals) against re-entering during the nested pass. */
    private static final ThreadLocal<Boolean> RENDERING_PIP = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static RenderTarget target;
    private static Entity ghostShell;
    private static boolean ghostFailed;
    private static boolean shaderWarningShown;

    public static boolean isRenderingPip() {
        return RENDERING_PIP.get();
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        if (RENDERING_PIP.get()) return;
        if (!ClientHitcam.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        if (shadersBlocked()) {
            warnShadersOnce(mc);
            ClientHitcam.stop();
            return;
        }

        int width = Mth.clamp(TBConfig.HITCAM_PIP_WIDTH.get(), 120, 1920);
        int height = Mth.clamp(TBConfig.HITCAM_PIP_HEIGHT.get(), 80, 1080);
        ensureTarget(width, height);

        ClientHitcam.advance(event.getPartialTick());

        try {
            RENDERING_PIP.set(Boolean.TRUE);
            renderPipScene(mc, width, height, event.getPartialTick());
        } catch (Throwable t) {
            // A secondary full-scene pass can break under other mods' render hooks;
            // the replay is cosmetic, so drop it instead of crashing the frame.
            ClientHitcam.stop();
        } finally {
            RENDERING_PIP.set(Boolean.FALSE);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    private static void renderPipScene(Minecraft mc, int width, int height, float partialTick) {
        Camera camera = new Camera();
        Vec3 camPos = ClientHitcam.cameraPos();
        Vec3 look = ClientHitcam.lookTarget().subtract(camPos);
        double horiz = Math.sqrt(look.x * look.x + look.z * look.z);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(look.y, horiz));
        camera.setup(mc.level, null, false, false, 1.0F);
        invokeSetPosition(camera, camPos);
        invokeSetRotation(camera, yaw, pitch);

        updateGhost(mc);

        target.bindWrite(true);
        RenderSystem.clear(256, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();

        Matrix4f projection = new Matrix4f().perspective(
            (float) Math.toRadians(mc.options.fov().get()),
            (float) width / height, 0.05F,
            Math.min(mc.options.renderDistance().get() * 16.0F + 64.0F, 1024.0F));

        mc.levelRenderer.renderLevel(
            new PoseStack(), partialTick, System.nanoTime(), false,
            camera, (GameRenderer) mc.gameRenderer, mc.gameRenderer.lightTexture(), projection);
        mc.renderBuffers().bufferSource().endBatch();

        renderGhost(mc, camera, partialTick);
        mc.renderBuffers().bufferSource().endBatch();
    }

    /**
     * The ghost shell is a non-tracked entity, so the nested level pass never
     * draws it; render it through the entity dispatcher, which renderLevel has
     * just prepared for our PiP camera. Falls back to a small additive streak
     * if the CBC renderer misbehaves with a fake entity.
     */
    private static void renderGhost(Minecraft mc, Camera camera, float partialTick) {
        if (ghostShell == null || mc.level == null) return;
        Vec3 cam = camera.getPosition();
        double rx = ghostShell.getX() - cam.x;
        double ry = ghostShell.getY() - cam.y;
        double rz = ghostShell.getZ() - cam.z;
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<? super Entity> renderer =
                mc.getEntityRenderDispatcher().getRenderer(ghostShell);
            if (renderer != null) {
                PoseStack pose = new PoseStack();
                var buffers = mc.renderBuffers().bufferSource();
                setGhostOrientation(ghostShell, ClientHitcam.shellDir());
                mc.getEntityRenderDispatcher().render(ghostShell, rx, ry, rz,
                    ghostShell.getYRot(), partialTick, pose, buffers,
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                buffers.endBatch();
                return;
            }
        } catch (Throwable ignored) {
        }
        renderGhostStreak(mc, camera);
    }

    private static void setGhostOrientation(Entity ghost, Vec3 dir) {
        try {
            java.lang.reflect.Field f = findField(ghost.getClass(), "orientation");
            f.set(ghost, dir.normalize());
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static java.lang.reflect.Field findField(Class<?> c, String name) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static void renderGhostStreak(Minecraft mc, Camera camera) {
        Vec3 pos = ClientHitcam.shellPos();
        Vec3 dir = ClientHitcam.shellDir();
        Vec3 cam = camera.getPosition();
        Vec3 mid = pos.subtract(dir.scale(0.5D));
        Vec3 toCamera = cam.subtract(mid);
        Vec3 side = dir.cross(toCamera);
        if (side.lengthSqr() < 1.0e-6D) side = new Vec3(1, 0, 0);
        side = side.normalize().scale(0.22D);
        Vec3 tail = pos.subtract(dir.scale(1.6D));
        PoseStack pose = new PoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        var buffer = buffers.getBuffer(net.minecraft.client.renderer.RenderType.lightning());
        Matrix4f matrix = pose.last().pose();
        quad(buffer, matrix, tail, pos, side, cam, 1.0F, 0.8F, 0.3F, 0.85F);
        buffers.endBatch(net.minecraft.client.renderer.RenderType.lightning());
    }

    private static void quad(com.mojang.blaze3d.vertex.VertexConsumer buffer, Matrix4f matrix, Vec3 start, Vec3 end,
                             Vec3 side, Vec3 cam, float red, float green, float blue, float alpha) {
        Vec3 s0 = start.subtract(side).subtract(cam);
        Vec3 s1 = start.add(side).subtract(cam);
        Vec3 e1 = end.add(side).subtract(cam);
        Vec3 e0 = end.subtract(side).subtract(cam);
        vertex(buffer, matrix, s0, red, green, blue, alpha * 0.2F);
        vertex(buffer, matrix, s1, red, green, blue, alpha * 0.2F);
        vertex(buffer, matrix, e1, red, green, blue, alpha);
        vertex(buffer, matrix, e0, red, green, blue, alpha);
    }

    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer buffer, Matrix4f matrix, Vec3 pos,
                               float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
            .color(red, green, blue, alpha).endVertex();
    }

    private static void updateGhost(Minecraft mc) {
        if (ghostShell == null && !ghostFailed) {
            Optional<EntityType<?>> type = EntityType.byString(ClientHitcam.entityTypeKey());
            if (type.isPresent() && mc.level != null) {
                try {
                    ghostShell = type.get().create(mc.level);
                } catch (Throwable t) {
                    ghostShell = null;
                }
            }
            if (ghostShell == null) ghostFailed = true;
        }
        if (ghostShell == null || mc.level == null) return;
        Vec3 pos = ClientHitcam.shellPos();
        ghostShell.setPos(pos.x, pos.y, pos.z);
        Vec3 dir = ClientHitcam.shellDir();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(dir.y, -1.0D, 1.0D)));
        ghostShell.setYRot(yaw);
        ghostShell.setXRot(pitch);
        ghostShell.yRotO = yaw;
        ghostShell.xRotO = pitch;
        ghostShell.xOld = ghostShell.xo = pos.x;
        ghostShell.yOld = ghostShell.yo = pos.y;
        ghostShell.zOld = ghostShell.zo = pos.z;
    }

    private static void ensureTarget(int width, int height) {
        if (target == null) {
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        } else if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
    }

    private static boolean shadersBlocked() {
        if (!TBConfig.HITCAM_DISABLE_WITH_SHADERS.get()) return false;
        return ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
    }

    private static void warnShadersOnce(Minecraft mc) {
        if (shaderWarningShown) return;
        shaderWarningShown = true;
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "CBCTB hitcam disabled: shader pack detected."), true);
        }
    }

    @SubscribeEvent
    public static void renderGui(RenderGuiEvent.Post event) {
        if (!ClientHitcam.active() || target == null) return;
        Minecraft mc = Minecraft.getInstance();
        var gui = event.getGuiGraphics();
        int screenW = gui.guiWidth();
        int screenH = gui.guiHeight();
        int drawW = Math.min(target.width, screenW - 4);
        int drawH = (int) ((long) drawW * target.height / Math.max(1, target.width));
        if (drawH > screenH - 4) {
            drawH = screenH - 4;
            drawW = (int) ((long) drawH * target.width / Math.max(1, target.height));
        }
        int x;
        int y;
        switch (TBConfig.HITCAM_PIP_ANCHOR.get()) {
            case "TOP_LEFT" -> { x = 2; y = 2; }
            case "BOTTOM_LEFT" -> { x = 2; y = screenH - drawH - 2; }
            case "BOTTOM_RIGHT" -> { x = screenW - drawW - 2; y = screenH - drawH - 2; }
            default -> { x = screenW - drawW - 2; y = 2; }
        }

        gui.fill(x - 1, y - 1, x + drawW + 1, y + drawH + 1, 0xFF202020);
        blitFramebuffer(gui.pose().last().pose(), x, y, drawW, drawH);
        gui.drawString(mc.font, ClientHitcam.statusLine(), x + 4, y + drawH - mc.font.lineHeight - 3, 0xFFFFFF, true);
    }

    private static void blitFramebuffer(Matrix4f matrix, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        BufferBuilder builder = RenderSystem.renderThreadTesselator().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        builder.vertex(matrix, x, y + h, 0).uv(0.0F, 1.0F).endVertex();
        builder.vertex(matrix, x + w, y + h, 0).uv(1.0F, 1.0F).endVertex();
        builder.vertex(matrix, x + w, y, 0).uv(1.0F, 0.0F).endVertex();
        builder.vertex(matrix, x, y, 0).uv(0.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
    }

    private static void invokeSetPosition(Camera camera, Vec3 pos) {
        callCamera(camera, "setPosition", new Class<?>[]{Vec3.class}, pos);
        callCamera(camera, "m_90581_", new Class<?>[]{Vec3.class}, pos);
    }

    private static void invokeSetRotation(Camera camera, float yaw, float pitch) {
        callCamera(camera, "setRotation", new Class<?>[]{float.class, float.class}, yaw, pitch);
        callCamera(camera, "m_90572_", new Class<?>[]{float.class, float.class}, yaw, pitch);
    }

    private static void callCamera(Camera camera, String name, Class<?>[] params, Object... args) {
        try {
            Method m = Camera.class.getDeclaredMethod(name, params);
            m.setAccessible(true);
            m.invoke(camera, args);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private ClientHitcamPiP() {}
}
