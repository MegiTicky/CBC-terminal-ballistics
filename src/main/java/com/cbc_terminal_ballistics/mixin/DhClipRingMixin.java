package com.cbc_terminal_ballistics.mixin;

import com.cbc_terminal_ballistics.client.hitcam.DistantHorizonsCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * DH hides its LOD terrain within {@code renderDistance*16*overdraw + 16}
 * blocks of the camera, because in the main view vanilla chunks always cover
 * that ring. With hitcam terrain streaming off the picture-in-picture has no
 * vanilla chunks at its camera at all: the {@code getRenderDistance=0} patch
 * (DhCameraMixin) already shrinks the render-distance term to ~0, but the
 * shader still adds this hard-coded +16 margin, clipping LODs within 16
 * blocks of the PiP camera — a hole right at the impact point in the
 * terminal close-up. Zero the margin while a DH-only PiP frame renders.
 * Stream mode keeps it intact: there it is exactly what keeps DH hidden
 * behind the streamed vanilla chunks.
 *
 * <p>DH is optional and unremapped, hence {@code @Pseudo} with
 * {@code remap = false, require = 0}: without DH (or on a DH build without
 * this exact constant) the mixin never applies and the view degrades to the
 * 16-block ring, never to a crash.</p>
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.DhTerrainShaderProgram")
public abstract class DhClipRingMixin {

    @ModifyConstant(method = "fillUniformData", constant = @Constant(floatValue = 16.0f), remap = false, require = 0)
    private float ctb$pipClipMargin(float original) {
        return DistantHorizonsCompat.shouldPatchCamera() && !TBConfig.HITCAM_TERRAIN_STREAM.get() ? 0.0f : original;
    }
}
