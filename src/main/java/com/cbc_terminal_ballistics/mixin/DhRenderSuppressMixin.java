package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Distant Horizons' LOD terrain out of the hitcam picture-in-picture
 * (hitcamDhMode=SUPPRESS, or ATTEMPT after a failure flipped the session to
 * suppress): DH's render entry points are cancelled while our nested world
 * pass renders, so the window shows only streamed vanilla chunks while the
 * main view keeps DH untouched. Cancelled at these four public entry points
 * rather than inside {@code renderLodLayer} so the profiler push/pop balance
 * and the deferred-transparent dispatch in the callers stay intact.
 *
 * <p>DH is an optional mod and its classes are not remapped, hence
 * {@code remap = false, require = 0}: without DH installed the mixin never
 * applies.</p>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi")
public abstract class DhRenderSuppressMixin {

    @Inject(method = "renderLods", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$suppressLods(CallbackInfo ci) {
        if (DistantHorizonsCompat.shouldSuppressLods()) ci.cancel();
    }

    @Inject(method = "renderDeferredLodsForShaders", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$suppressDeferredLods(CallbackInfo ci) {
        if (DistantHorizonsCompat.shouldSuppressLods()) ci.cancel();
    }

    @Inject(method = "renderFadeOpaque", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$suppressFadeOpaque(CallbackInfo ci) {
        if (DistantHorizonsCompat.shouldSuppressLods()) ci.cancel();
    }

    @Inject(method = "renderFadeTransparent", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$suppressFadeTransparent(CallbackInfo ci) {
        if (DistantHorizonsCompat.shouldSuppressLods()) ci.cancel();
    }
}
