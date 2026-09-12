package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.ClientHitcamPiP;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the nested PiP pass out of vanilla's entity-glow outline pipeline.
 *
 * <p>Vanilla computes the glow halo per {@code renderLevel} call: each pass
 * draws flat silhouette colors, runs the entity_outline post chain, and
 * stores the halo in a shared "final" target that GameRenderer composites
 * onto the screen ONCE at the end of the frame. The nested PiP pass ran that
 * pipeline a second time mid-frame and overwrote the shared target with the
 * PiP camera's halo, so the main view wore the PiP view's glow — flickering
 * as the PiP camera caught or missed the glowing entity. Skipping outlines
 * entirely while the pass renders keeps the main view's halo intact;
 * entities still render solid inside the PiP window.</p>
 *
 * <p>Dual-name pattern (see vanilla-target mixin lesson): production Forge
 * 1.20.1 runs SRG members, dev runtimes official names — exactly one
 * resolves per environment and a miss is a silent no-op.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOutlineMixin {

    @Inject(method = "m_109817_()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipDisableOutlinesSrg(CallbackInfoReturnable<Boolean> cir) {
        if (ClientHitcamPiP.isRenderingPip()) cir.setReturnValue(false);
    }

    @Inject(method = "shouldRenderEntityOutlines()Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipDisableOutlinesOfficial(CallbackInfoReturnable<Boolean> cir) {
        if (ClientHitcamPiP.isRenderingPip()) cir.setReturnValue(false);
    }
}
