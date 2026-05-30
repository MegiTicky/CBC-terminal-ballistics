package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.ballistics.ImpactMarkKind;
import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

public record ImpactMark(ImpactMarkKind kind, TBCaliber caliber, ImpactSurfaceType surface, Direction face, float x, float y, float z, float rotation, long gameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Kind", (byte) kind.ordinal());
        tag.putByte("Caliber", (byte) caliber.ordinal());
        tag.putByte("Surface", (byte) surface.ordinal());
        tag.putByte("Face", (byte) face.get3DDataValue());
        tag.putFloat("X", x);
        tag.putFloat("Y", y);
        tag.putFloat("Z", z);
        tag.putFloat("Rot", rotation);
        tag.putLong("Time", gameTime);
        return tag;
    }

    public static ImpactMark load(CompoundTag tag) {
        ImpactMarkKind[] kinds = ImpactMarkKind.values();
        TBCaliber[] calibers = TBCaliber.values();
        ImpactSurfaceType[] surfaces = ImpactSurfaceType.values();
        int k = Math.max(0, Math.min(kinds.length - 1, tag.getByte("Kind")));
        int c = tag.contains("Caliber") ? Math.max(0, Math.min(calibers.length - 1, tag.getByte("Caliber"))) : TBCaliber.SMALL.ordinal();
        int s = tag.contains("Surface") ? Math.max(0, Math.min(surfaces.length - 1, tag.getByte("Surface"))) : ImpactSurfaceType.METALLIC.ordinal();
        float rotation = tag.contains("Rot") ? tag.getFloat("Rot") : 0.0F;
        return new ImpactMark(kinds[k], calibers[c], surfaces[s], Direction.from3DDataValue(tag.getByte("Face")), tag.getFloat("X"), tag.getFloat("Y"), tag.getFloat("Z"), rotation, tag.getLong("Time"));
    }

    public Vec3 absolute(net.minecraft.core.BlockPos pos) {
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }
}
