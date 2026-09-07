package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controls which particles render inside the hitcam picture-in-picture.
 *
 * <p>CBC's cannon muzzle plume spawns inside the artillery cam the moment the
 * shell does, and vanilla/CBC smoke would otherwise fill the window. The
 * {@code hitcamPipParticles} config picks the behaviour: NONE hides all
 * particles, FILTERED hides only the smoke family (class names containing
 * "smoke" or "plume" — cannon plumes, smoke trails, breech smoke, vanilla
 * smoke) so explosions, fire, cook-off and debris stay visible, and ALL shows
 * everything. The main view is never touched.</p>
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

    private static final String PARTICLE_RENDER_TARGET_SRG =
        "Lnet/minecraft/client/particle/Particle;m_5744_(Lcom/mojang/blaze3d/vertex/VertexConsumer;"
        + "Lnet/minecraft/client/Camera;F)V";

    private static final String PARTICLE_RENDER_TARGET_NAMED =
        "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;"
        + "Lnet/minecraft/client/Camera;F)V";

    @Inject(method = "m_107336_(" + RENDER_DESCRIPTOR, remap = false, require = 0, at = @At("HEAD"), cancellable = true)
    private void ctb$skipPipParticlesSrg(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                         LightTexture lightTexture, Camera camera, float partialTick,
                                         CallbackInfo ci) {
        if (ClientHitcamPiP.hideAllPipParticles()) {
            ci.cancel();
        }
    }

    @Inject(method = "render(" + RENDER_DESCRIPTOR, remap = false, require = 0, at = @At("HEAD"), cancellable = true)
    private void ctb$skipPipParticlesNamed(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           LightTexture lightTexture, Camera camera, float partialTick,
                                           CallbackInfo ci) {
        if (ClientHitcamPiP.hideAllPipParticles()) {
            ci.cancel();
        }
    }

    /** FILTERED mode: drop individual smoke-family particles instead of the whole pass. */
    @Redirect(method = "m_107336_(" + RENDER_DESCRIPTOR, remap = false, require = 0,
        at = @At(value = "INVOKE", target = PARTICLE_RENDER_TARGET_SRG))
    private void ctb$filterPipParticleSrg(Particle particle, VertexConsumer vertexConsumer, Camera camera,
                                          float partialTick) {
        if (ClientHitcamPiP.shouldRenderPipParticle(particle)) {
            particle.render(vertexConsumer, camera, partialTick);
        }
    }

    @Redirect(method = "render(" + RENDER_DESCRIPTOR, remap = false, require = 0,
        at = @At(value = "INVOKE", target = PARTICLE_RENDER_TARGET_NAMED))
    private void ctb$filterPipParticleNamed(Particle particle, VertexConsumer vertexConsumer, Camera camera,
                                            float partialTick) {
        if (ClientHitcamPiP.shouldRenderPipParticle(particle)) {
            particle.render(vertexConsumer, camera, partialTick);
        }
    }
}
