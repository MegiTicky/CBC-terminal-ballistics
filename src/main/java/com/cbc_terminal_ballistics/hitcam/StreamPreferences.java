package com.cbc_terminal_ballistics.hitcam;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side per-player view of the client's hitcam streaming preferences,
 * relayed by {@code ServerboundStreamPreferencePacket}. Two independent
 * switches, both defaulting to true — clients that never send (older builds,
 * other mod versions) keep today's streaming behaviour:
 * <ul>
 *   <li><b>artillery</b> — {@code hitcam.terrainStream}: stream chunks around
 *       the flying shell for the live chase cam (false = rely on Distant
 *       Horizons LODs in that fast-moving view).</li>
 *   <li><b>terminal</b> — {@code hitcam.terminalStream}: stream chunks for the
 *       terminal x-ray. The x-ray is exempt from the artillery preference: a
 *       stationary orbit over a real engagement needs real chunks, and the
 *       view is short so the cost is small. Only honoured once the shell has
 *       actually engaged something (recorded a plate or blast).</li>
 * </ul>
 * Entries are removed on logout so a stale preference cannot outlive the
 * client that sent it.
 */
public final class StreamPreferences {
    private static final Map<UUID, boolean[]> PREFERENCES = new ConcurrentHashMap<>();

    public static void set(UUID playerId, boolean artillery, boolean terminal) {
        PREFERENCES.put(playerId, new boolean[] { artillery, terminal });
    }

    public static void clear(UUID playerId) {
        PREFERENCES.remove(playerId);
    }

    /** True when the server may stream the shell's flight for the artillery chase cam (default true). */
    public static boolean wantsArtillery(ServerPlayer player) {
        return prefs(player)[0];
    }

    /** True when the server may stream chunks for this viewer's terminal x-ray (default true). */
    public static boolean wantsTerminal(ServerPlayer player) {
        return prefs(player)[1];
    }

    private static boolean[] prefs(ServerPlayer player) {
        if (player == null) return new boolean[] { false, false };
        return PREFERENCES.getOrDefault(player.getUUID(), new boolean[] { true, true });
    }

    private StreamPreferences() {}
}
