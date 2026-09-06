package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client-side retention for the artillery/terminal view's streamed terrain.
 * Vanilla {@code ClientChunkCache} only stores chunks within the player's view
 * square and serves null everywhere else, so chunks the server sends around a
 * far-flying shell would be dropped and the hitcam PiP's nested world pass
 * would render nothing but sky past the render distance boundary. Chunks the
 * {@code ClientChunkCacheMixin} rescues from that discard path are parked here
 * and served back from the cache's {@code getChunk} fallback, giving the PiP
 * camera real terrain all the way to impact.
 *
 * <p>All access happens on the client thread: chunk packets are re-queued to
 * the main thread by {@code ClientPacketListener} and the render-time
 * {@code getChunk} calls run on the render thread, which is the same thread in
 * practice for a single-player world.</p>
 */
public final class ArtilleryChunkCache {
    /** Chunks beyond the player's view square, keyed by packed ChunkPos. */
    private static final Map<Long, LevelChunk> CHUNKS = new HashMap<>();
    /** True while the artillery/terminal view wants streamed terrain. */
    private static boolean active;
    /** Cheap short-circuit for the hot {@code getChunk} fallback. */
    private static boolean empty = true;
    /** One-shot diagnostics: prove which links of the streaming chain run. */
    private static boolean captureHookProbed;
    private static boolean serveHookProbed;
    private static boolean activationLogged;
    private static boolean captureLogged;

    /**
     * Called by the mixin's capture handler on every chunk packet. Logs once
     * that the injection actually applied (its absence in a log means the
     * injector never matched), and gates on the capturing flag.
     */
    public static boolean probeCaptureHook() {
        if (!captureHookProbed) {
            captureHookProbed = true;
            CBCTerminalBallistics.LOGGER.info("[CTB-CHUNKSTREAM] ClientChunkCache capture hook applied, capturing={}", capturing());
        }
        return capturing();
    }

    public static void probeServeHook(int chunkX, int chunkZ) {
        if (serveHookProbed) return;
        serveHookProbed = true;
        CBCTerminalBallistics.LOGGER.info("[CTB-CHUNKSTREAM] first chunk served to renderer at {}, {}", chunkX, chunkZ);
    }

    public static void setActive(boolean value) {
        boolean enabled = value && TBConfig.HITCAM_ENABLED.get() && TBConfig.HITCAM_TERRAIN_STREAM.get();
        if (active == enabled) return;
        active = enabled;
        if (active && !activationLogged) {
            activationLogged = true;
            CBCTerminalBallistics.LOGGER.info("[CTB-CHUNKSTREAM] terrain retention activated");
        }
        if (!active) clear();
    }

    public static boolean capturing() {
        return active;
    }

    public static boolean isEmpty() {
        return empty;
    }

    @javax.annotation.Nullable
    public static LevelChunk get(int chunkX, int chunkZ) {
        return CHUNKS.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static void put(LevelChunk chunk) {
        CHUNKS.put(chunk.getPos().toLong(), chunk);
        empty = false;
        if (!captureLogged) {
            captureLogged = true;
            CBCTerminalBallistics.LOGGER.info("[CTB-CHUNKSTREAM] first chunk captured at {}", chunk.getPos());
        }
    }

    /**
     * Drops chunks that fell well behind the shell, unloading their block
     * entities and marking their sections dirty so the compiled meshes are
     * rebuilt empty (freeing the VBOs). Called periodically from the hitcam
     * client tick.
     */
    public static void sweep(double shellX, double shellZ) {
        if (empty) return;
        int shellCx = Mth.floor(shellX) >> 4;
        int shellCz = Mth.floor(shellZ) >> 4;
        int keep = Mth.clamp(TBConfig.HITCAM_TERRAIN_STREAM_RADIUS.get(), 1, 4) + 8;
        Iterator<Map.Entry<Long, LevelChunk>> it = CHUNKS.entrySet().iterator();
        while (it.hasNext()) {
            LevelChunk chunk = it.next().getValue();
            ChunkPos pos = chunk.getPos();
            if (Math.max(Math.abs(pos.x - shellCx), Math.abs(pos.z - shellCz)) <= keep) continue;
            it.remove();
            discard(chunk);
        }
        empty = CHUNKS.isEmpty();
    }

    /** Drops everything; used when the view ends or the level unloads. */
    public static void clear() {
        if (empty) return;
        for (LevelChunk chunk : CHUNKS.values()) {
            discard(chunk);
        }
        CHUNKS.clear();
        empty = true;
    }

    private static void discard(LevelChunk chunk) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.unload(chunk);
        ChunkPos pos = chunk.getPos();
        for (int y = mc.level.getMinSection(); y < mc.level.getMaxSection(); y++) {
            mc.levelRenderer.setSectionDirty(pos.x, y, pos.z);
        }
    }

    private ArtilleryChunkCache() {}
}
