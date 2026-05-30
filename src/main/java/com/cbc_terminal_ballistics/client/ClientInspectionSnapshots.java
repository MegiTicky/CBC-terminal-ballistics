package com.cbc_terminal_ballistics.client;

import com.cbc_terminal_ballistics.debug.InspectionSnapshot;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientInspectionSnapshots {
    private static final Map<BlockPos, InspectionSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    public static void accept(InspectionSnapshot snapshot) {
        SNAPSHOTS.put(snapshot.pos(), snapshot);
    }

    public static InspectionSnapshot get(BlockPos pos) {
        return SNAPSHOTS.get(pos);
    }

    private ClientInspectionSnapshots() {}
}
