package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** A shell visual anchored to a block-local impact position. */
public record EmbeddedShell(UUID id, TBCaliber caliber, Direction face, float x, float y, float z,
                            float directionX, float directionY, float directionZ, long gameTime) {
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putByte("Caliber", (byte) caliber.ordinal());
        tag.putByte("Face", (byte) face.get3DDataValue());
        tag.putFloat("X", x);
        tag.putFloat("Y", y);
        tag.putFloat("Z", z);
        tag.putFloat("DX", directionX);
        tag.putFloat("DY", directionY);
        tag.putFloat("DZ", directionZ);
        tag.putLong("Time", gameTime);
        return tag;
    }

    public static EmbeddedShell load(CompoundTag tag) {
        TBCaliber[] calibers = TBCaliber.values();
        int caliberIndex = Math.max(0, Math.min(calibers.length - 1, tag.getByte("Caliber")));
        return new EmbeddedShell(
            tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID(),
            calibers[caliberIndex],
            Direction.from3DDataValue(tag.getByte("Face")),
            tag.getFloat("X"),
            tag.getFloat("Y"),
            tag.getFloat("Z"),
            tag.getFloat("DX"),
            tag.getFloat("DY"),
            tag.getFloat("DZ"),
            tag.getLong("Time")
        );
    }
}
