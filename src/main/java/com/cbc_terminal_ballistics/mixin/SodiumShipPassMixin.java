package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the window in which VS2 draws a single ship's terrain sections inside
 * the sodium/embeddium chunk renderer, so the terminal x-ray can ghost exactly
 * that geometry (config hitcamPipTerrainAlpha) and nothing else.
 *
 * <p>The tt16 VS2 fork renders ship terrain under sodium by redirecting
 * {@code RenderSectionManager}'s layer draw: the world layer first, then one
 * transformed {@code ChunkRenderer.render} call per ship from
 * {@code lambda$redirectRenderLayer$0}. Toggling a flag at that lambda's
 * boundaries brackets exactly the per-ship terrain draws; the x-ray state
 * itself is installed by {@link EmbeddiumTerrainGhostMixin} around
 * {@code DefaultChunkRenderer.render}, after the pass state setup.</p>
 *
 * <p>Targets a mod class (and a synthetic lambda), so {@code remap = false};
 * {@code require = 0} so a fork without this hook degrades to no ghosting
 * instead of crashing.</p>
 */
@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.forge.mixin.compat.sodium.MixinRenderSectionManager", remap = false)
public abstract class SodiumShipPassMixin {

    @Inject(method = "lambda$redirectRenderLayer$0*", at = @At("HEAD"), require = 0, cancellable = true)
    private void ctb$shipTerrainPassStart(CallbackInfo ci) {
        ClientHitcamPiP.setShipTerrainPass(true);
    }

    @Inject(method = "lambda$redirectRenderLayer$0*", at = @At("RETURN"), require = 0)
    private void ctb$shipTerrainPassEnd(CallbackInfo ci) {
        ClientHitcamPiP.setShipTerrainPass(false);
    }
}
