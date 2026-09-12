package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.DistantHorizonsCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
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
            DistantHorizonsCompat.countPatchRedirect();
            cir.setReturnValue(vec);
        }
    }

    @Inject(method = "getLookAtVector", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipLookVector(CallbackInfoReturnable<Object> cir) {
        if (!DistantHorizonsCompat.shouldPatchCamera()) return;
        Object vec = DistantHorizonsCompat.makeDhVec3f(DistantHorizonsCompat.pipLookDirection());
        if (vec != null) {
            DistantHorizonsCompat.countPatchRedirect();
            cir.setReturnValue(vec);
        }
    }

    /**
     * DH hides its LOD terrain inside the vanilla render distance: the terrain
     * shader clips fragments within renderDistance*16*overdraw+16 blocks of
     * the camera and the vanilla-fade gradient uses the same radius — correct
     * for the main view, where vanilla terrain always covers that ring, but in
     * the picture-in-picture only the streamed radius (default 2 chunks)
     * exists around the shell, so everything up to ~300 blocks was a hole.
     * Reporting the stream radius (minus the shader's extra 16-block margin)
     * during the nested pass pulls that ring inside the streamed area; with
     * terrain streaming disabled it reports 0, so DH fills the whole window.
     */
    @Inject(method = "getRenderDistance", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ctb$pipRenderDistance(CallbackInfoReturnable<Integer> cir) {
        if (!DistantHorizonsCompat.shouldPatchCamera()) return;
        // DH's terrain shader clips LOD fragments within renderDistance*16*overdraw+16
        // blocks of the camera, so the reported distance must stay one chunk below
        // the streamed radius for the clipped ring to remain covered by vanilla.
        cir.setReturnValue(TBConfig.HITCAM_TERRAIN_STREAM.get()
            ? Math.max(0, TBConfig.HITCAM_TERRAIN_STREAM_RADIUS.get() - 1)
            : 0);
    }
}
