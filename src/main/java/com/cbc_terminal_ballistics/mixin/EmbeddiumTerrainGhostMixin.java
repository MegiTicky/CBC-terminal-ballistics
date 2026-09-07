package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs the x-ray ghost GL state around embeddium's terrain draw whenever
 * VS2 is inside a per-ship terrain pass (see {@link SodiumShipPassMixin}) and
 * the terminal view is up: a fixed-function constant-alpha blend so every
 * ship block composites at hitcamPipTerrainAlpha — embeddium's chunk shader
 * ignores RenderSystem.setShaderColor, so the blend factor is the only
 * renderer-agnostic alpha knob — plus depth writes off in x-ray mode so hull
 * interiors and the turrets behind them stay visible through the hull.
 *
 * <p>Injected after {@code ShaderChunkRenderer.begin} (pass state setup) and
 * restored before {@code end}, so the world terrain pass and everything
 * outside the bracket are untouched. Targets a mod class, so
 * {@code remap = false}; {@code require = 0} so other embeddium versions
 * degrade to no ghosting instead of crashing.</p>
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class EmbeddiumTerrainGhostMixin {

    @Inject(method = "render*", remap = false, require = 0,
        at = @At(value = "INVOKE", remap = false,
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;begin(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
            shift = At.Shift.AFTER))
    private void ctb$applyShipGhost(CallbackInfo ci) {
        ClientHitcamPiP.applyShipGhostGl();
    }

    @Inject(method = "render*", remap = false, require = 0,
        at = @At(value = "INVOKE", remap = false,
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lme/jellysquid/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V",
            shift = At.Shift.BEFORE))
    private void ctb$clearShipGhost(CallbackInfo ci) {
        ClientHitcamPiP.clearShipGhostGl();
        // Self-heal a flag stranded by an exception inside a ship pass, so a
        // later world pass cannot inherit the ghost state.
        ClientHitcamPiP.setShipTerrainPass(false);
    }
}
