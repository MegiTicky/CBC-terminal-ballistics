package com.cbc_terminal_ballistics.hitcam;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-player view of the client's {@code hitcam.terrainStream}
 * preference, relayed by {@code ServerboundStreamPreferencePacket}. Defaults
 * to true — clients that never send (older builds, other mod versions) keep
 * today's streaming behaviour. Entries are removed on logout so a stale
 * preference cannot outlive the client that sent it.
 */
public final class StreamPreferences {
    private static final Map<UUID, Boolean> PREFERENCES = new ConcurrentHashMap<>();

    public static void set(UUID playerId, boolean enabled) {
        PREFERENCES.put(playerId, enabled);
    }

    public static void clear(UUID playerId) {
        PREFERENCES.remove(playerId);
    }

    /** True when the server may stream chunks to this viewer (default true). */
    public static boolean isEnabled(ServerPlayer player) {
        if (player == null) return false;
        return PREFERENCES.getOrDefault(player.getUUID(), Boolean.TRUE);
    }

    private StreamPreferences() {}
}
