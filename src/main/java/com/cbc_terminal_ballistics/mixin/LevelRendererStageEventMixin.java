package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppresses third-party Forge world overlays during the nested PiP pass. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererStageEventMixin {
    private static final String DISPATCH =
        "Lnet/minecraftforge/client/ForgeHooksClient;dispatchRenderStage(" +
        "Lnet/minecraftforge/client/event/RenderLevelStageEvent$Stage;" +
        "Lnet/minecraft/client/renderer/LevelRenderer;" +
        "Lcom/mojang/blaze3d/vertex/PoseStack;" +
        "Lorg/joml/Matrix4f;ILnet/minecraft/client/Camera;" +
        "Lnet/minecraft/client/renderer/culling/Frustum;)V";

    @Redirect(method = "m_109599_(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = DISPATCH), remap = false, require = 0)
    private void ctb$suppressNestedStagesSrg(RenderLevelStageEvent.Stage stage, LevelRenderer renderer,
                                               PoseStack pose, Matrix4f projection, int tick, Camera camera, Frustum frustum) {
        if (!ClientHitcamPiP.isRenderingPip()) ForgeHooksClient.dispatchRenderStage(stage, renderer, pose, projection, tick, camera, frustum);
    }

    @Redirect(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At(value = "INVOKE", target = DISPATCH), remap = false, require = 0)
    private void ctb$suppressNestedStagesNamed(RenderLevelStageEvent.Stage stage, LevelRenderer renderer,
                                                  PoseStack pose, Matrix4f projection, int tick, Camera camera, Frustum frustum) {
        if (!ClientHitcamPiP.isRenderingPip()) ForgeHooksClient.dispatchRenderStage(stage, renderer, pose, projection, tick, camera, frustum);
    }
}
