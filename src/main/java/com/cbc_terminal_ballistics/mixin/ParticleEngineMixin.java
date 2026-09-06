package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CBC's cannon smoke (muzzle clouds, shell trails, impact dust) fills the
 * hitcam picture-in-picture during exactly the seconds the replay watches —
 * the artillery cam spawns inside the firing plume, so the window shows pure
 * smoke instead of the shell. Particles are skipped for the whole PiP pass;
 * the main view is untouched.
 *
 * <p>Production Forge 1.20.1 runs vanilla members under SRG names while dev
 * runtimes use official ones, and this workspace's Mixin AP has no vanilla
 * mapping table, so both spellings are injected with {@code remap = false,
 * require = 0} — exactly one of them resolves per environment, and a miss is
 * a silent no-op (worst case: the PiP shows smoke again).</p>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

    private static final String RENDER_DESCRIPTOR =
        "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
        + "Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V";

    @Inject(method = "m_107336_(" + RENDER_DESCRIPTOR, remap = false, require = 0, at = @At("HEAD"), cancellable = true)
    private void ctb$skipPipParticlesSrg(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                         LightTexture lightTexture, Camera camera, float partialTick,
                                         CallbackInfo ci) {
        if (ClientHitcamPiP.isRenderingPip()) {
            ci.cancel();
        }
    }

    @Inject(method = "render(" + RENDER_DESCRIPTOR, remap = false, require = 0, at = @At("HEAD"), cancellable = true)
    private void ctb$skipPipParticlesNamed(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           LightTexture lightTexture, Camera camera, float partialTick,
                                           CallbackInfo ci) {
        if (ClientHitcamPiP.isRenderingPip()) {
            ci.cancel();
        }
    }
}
