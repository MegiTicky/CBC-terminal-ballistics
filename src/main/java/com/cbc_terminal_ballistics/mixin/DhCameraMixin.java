package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Points Distant Horizons' render camera at the hitcam picture-in-picture
 * camera while our nested world pass renders (hitcamDhMode=ATTEMPT). DH
 * captures view/projection matrices per renderChunkLayer call — so they are
 * already correct inside the nested pass — but reads the camera position from
 * Minecraft.gameRenderer.getMainCamera() (always the player), which displaces
 * its LOD terrain by the player-to-shell distance in the window. Every DH
 * render-side consumer goes through these two wrapper methods (RenderParams
 * camera position, fog shader height, generic object/cloud/beacon renderers),
 * so patching here fixes them all at once.
 *
 * <p>DH is an optional mod and its classes are not remapped, hence
 * {@code remap = false, require = 0}: without DH installed the mixin never
 * applies. Return values are DH's own math types, built reflectively in
 * {@link DistantHorizonsCompat} so this mod never needs DH at compile time.</p>
 */
@Pseudo
@Mixin(targets = "loaderCommon.forge.com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper")
public abstract class DhCameraMixin {

    @Inject(method = "getCameraExactPosition", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipCameraPosition(CallbackInfoReturnable<Object> cir) {
        if (!DistantHorizonsCompat.shouldPatchCamera()) return;
        Object vec = DistantHorizonsCompat.makeDhVec3d(DistantHorizonsCompat.pipCameraPosition());
        if (vec != null) {
            cir.setReturnValue(vec);
        }
    }

    @Inject(method = "getLookAtVector", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipLookVector(CallbackInfoReturnable<Object> cir) {
        if (!DistantHorizonsCompat.shouldPatchCamera()) return;
        Object vec = DistantHorizonsCompat.makeDhVec3f(DistantHorizonsCompat.pipLookDirection());
        if (vec != null) {
            cir.setReturnValue(vec);
        }
    }
}
