package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.armor.FramedCollapsibleCopycatArmorBlock;
import com.copycatsplus.copycats.foundation.copycat.model.CopycatModelCore;
import com.copycatsplus.copycats.foundation.copycat.model.assembly.AssemblyTransform;
import com.copycatsplus.copycats.foundation.copycat.model.assembly.CopycatRenderContext;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public class FramedCollapsibleCopycatArmorModelCore extends CopycatModelCore.WithData<Integer> {
    private static final int UP = Direction.UP.ordinal();
    private static final int DOWN = Direction.DOWN.ordinal();
    private static final int NORTH = Direction.NORTH.ordinal();
    private static final int EAST = Direction.EAST.ordinal();
    private static final int SOUTH = Direction.SOUTH.ordinal();
    private static final int WEST = Direction.WEST.ordinal();

    @Override
    public void emitCopycatQuads(String key, BlockState state, CopycatRenderContext context, BlockState material) {
        int packedOffsets = getData() == null ? 0 : getData();
        byte[] offsets = FramedCollapsibleCopycatArmorBlock.unpackOffsets(packedOffsets);

        double x0 = offsets[WEST];
        double y0 = offsets[DOWN];
        double z0 = offsets[NORTH];
        double x1 = 16 - offsets[EAST];
        double y1 = 16 - offsets[UP];
        double z1 = 16 - offsets[SOUTH];
        double width = x1 - x0;
        double height = y1 - y0;
        double depth = z1 - z0;

        if (width <= 0 || height <= 0 || depth <= 0) {
            return;
        }

        context.assemblePiece(
                AssemblyTransform.IDENTITY,
                CopycatRenderContext.vec3(x0, y0, z0),
                CopycatRenderContext.aabb(width, height, depth).move(x0, y0, z0),
                CopycatRenderContext.cull(0),
                CopycatRenderContext.autoCull()
        );
    }
}
