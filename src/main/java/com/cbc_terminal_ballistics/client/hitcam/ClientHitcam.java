package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client-side hitcam state. Three mutually exclusive modes, newest event wins:
 * REPLAY (the manual /cbctb hitcam ghost playback over recorded samples),
 * ARTILLERY (a live chase camera locked onto a just-fired projectile entity)
 * and TERMINAL (the post-impact x-ray diagram orbit view). The live world
 * keeps ticking untouched in every mode.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHitcam {
    private static final int HOLD_TICKS = 40;

    private enum Mode { NONE, REPLAY, ARTILLERY, TERMINAL }

    private static Mode mode = Mode.NONE;

    // REPLAY state
    private static String entityTypeKey = "";
    private static String outcomeSummary;
    private static List<HitcamSample> samples = List.of();
    private static double playHead;
    private static double span;
    private static int holdTicks;
    private static float lastPartialTick = -1.0F;
    private static float currentSpeed = 1.0F;

    // ARTILLERY state
    private static int artilleryEntityId = -1;
    // Server-relayed shell state for when the projectile is beyond client
    // tracking range (CBC keeps simulating it server-side; the vanilla tracker
    // has despawned the client copy there).
    private static String artilleryGhostTypeKey = "";
    private static Vec3 artilleryGhostPos = Vec3.ZERO;
    private static Vec3 artilleryGhostVel = Vec3.ZERO;
    private static long artilleryGhostUpdateGameTime;
    private static boolean artilleryGhostAlive;
    /** Ticks of silence after which the relay is considered lost. */
    private static final int ARTILLERY_GHOST_TIMEOUT_TICKS = 10;
    /** Ticks after {@link #startArtillery} during which the view cannot end, bridging
     * the gap until the first relay for a retargeted (e.g. rebind) shell arrives. */
    private static final int ARTILLERY_START_GRACE_TICKS = 5;
    private static int artilleryGraceTicks;
    /** Game time of the last relay packet with live data; keeps streamed terrain cached a moment past view-mode flickers. */
    private static long lastArtilleryRelayGameTime = Long.MIN_VALUE;

    public static void start(String typeKey, List<HitcamSample> recorded, String summary) {
        if (recorded.size() < 2) return;
        mode = Mode.REPLAY;
        entityTypeKey = typeKey == null ? "" : typeKey;
        outcomeSummary = summary;
        samples = List.copyOf(recorded);
        span = samples.size() - 1;
        playHead = 0.0D;
        holdTicks = 0;
        lastPartialTick = -1.0F;
        currentSpeed = 1.0F;
        artilleryEntityId = -1;
        ClientTerminalView.clear();
    }

    /** Artillery view: follow this live projectile entity until it is gone. */
    public static void startArtillery(int entityId) {
        if (!TBConfig.HITCAM_ENABLED.get()) return;
        // A newly fired shell must not wipe a terminal animation that is still
        // playing (salvo fire made the x-ray flash for a moment, then vanish
        // when the next gun's track packet arrived). Its own terminal packet
        // parks and plays when the current animation finishes. While burst
        // accumulation is on, the whole live stage is protected: a follow-up
        // shell from the same vehicle should merge into the running x-ray, not
        // yank the view into an artillery chase cam.
        if (mode == Mode.TERMINAL && (ClientTerminalView.animationInProgress()
            || (TBConfig.HITCAM_TERMINAL_BURST.get() && !ClientTerminalView.expired()))) {
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-HITCAM] ignoring track packet for shell {} — terminal {} in progress", entityId,
                ClientTerminalView.animationInProgress() ? "animation" : "burst hold");
            return;
        }
        mode = Mode.ARTILLERY;
        artilleryEntityId = entityId;
        artilleryGraceTicks = ARTILLERY_START_GRACE_TICKS;
        clearArtilleryGhost();
        ClientTerminalView.clear();
        resetReplay();
    }

    /** Terminal view packet arrived; called by {@link ClientTerminalView#accept}. */
    public static void startTerminalView() {
        mode = Mode.TERMINAL;
        artilleryEntityId = -1;
        clearArtilleryGhost();
        resetReplay();
    }

    public static void stop() {
        mode = Mode.NONE;
        artilleryEntityId = -1;
        artilleryGraceTicks = 0;
        clearArtilleryGhost();
        ArtilleryChunkCache.setActive(false);
        ClientTerminalView.clear();
        resetReplay();
    }

    private static void clearArtilleryGhost() {
        artilleryGhostTypeKey = "";
        artilleryGhostPos = Vec3.ZERO;
        artilleryGhostVel = Vec3.ZERO;
        artilleryGhostAlive = false;
    }

    /** Server relay: authoritative shell state from beyond tracking range. */
    public static void onArtilleryUpdate(int entityId, String typeKey, Vec3 pos, Vec3 vel, boolean removed) {
        if (!TBConfig.HITCAM_ENABLED.get()) return;
        if (removed) {
            // Only the followed shell's death may end the view. Removal notices
            // for other shells (e.g. an older one expiring somewhere else) must
            // not kill the current chase cam.
            if (mode == Mode.ARTILLERY && artilleryEntityId >= 0 && entityId != artilleryEntityId) return;
            artilleryGhostAlive = false;
            return;
        }
        if (mode == Mode.ARTILLERY && artilleryEntityId >= 0 && artilleryEntityId != entityId) return;
        if (mode == Mode.NONE) {
            mode = Mode.ARTILLERY;
            artilleryEntityId = entityId;
            ClientTerminalView.clear();
            resetReplay();
        }
        artilleryGhostTypeKey = typeKey == null ? "" : typeKey;
        artilleryGhostPos = pos;
        artilleryGhostVel = vel;
        artilleryGhostAlive = true;
        Minecraft mc = Minecraft.getInstance();
        artilleryGhostUpdateGameTime = mc.level != null ? mc.level.getGameTime() : 0L;
        lastArtilleryRelayGameTime = artilleryGhostUpdateGameTime;
    }

    /**
     * True while a server relay with live shell data arrived recently. Streamed
     * terrain stays cached through this window even if the view mode flickers,
     * so captured chunks are not wiped the instant the view ends.
     */
    private static boolean relayFresh() {
        if (lastArtilleryRelayGameTime == Long.MIN_VALUE) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getGameTime() - lastArtilleryRelayGameTime <= 40;
    }

    /**
     * Keeps the streamed-terrain retention in {@link ArtilleryChunkCache} in
     * step with the view: active while the artillery cam or terminal view is
     * live (or the server relay is still flowing), with a periodic sweep
     * dropping chunks the shell left far behind.
     */
    private static void updateTerrainCache(Minecraft mc) {
        boolean live = mode == Mode.ARTILLERY || mode == Mode.TERMINAL || relayFresh();
        ArtilleryChunkCache.setActive(live);
        if (live && mc.level.getGameTime() % 20 == 0) {
            Vec3 shell = shellPos();
            ArtilleryChunkCache.sweep(shell.x, shell.z);
            ArtilleryChunkCache.logSummaryIfDue(mc.level.getGameTime());
        }
    }

    private static void resetReplay() {
        samples = List.of();
        outcomeSummary = null;
        playHead = 0.0D;
        span = 0.0D;
        holdTicks = 0;
        lastPartialTick = -1.0F;
        currentSpeed = 1.0F;
    }

    public static boolean active() {
        return switch (mode) {
            case REPLAY -> samples.size() >= 2;
            case ARTILLERY -> hasLiveArtilleryTarget();
            case TERMINAL -> ClientTerminalView.active();
            default -> false;
        };
    }

    public static boolean isReplay() {
        return mode == Mode.REPLAY;
    }

    public static boolean isArtillery() {
        return mode == Mode.ARTILLERY && hasLiveArtilleryTarget();
    }

    public static boolean isTerminal() {
        return mode == Mode.TERMINAL && ClientTerminalView.active();
    }

    /** The live projectile entity the artillery cam is following, or null. */
    @javax.annotation.Nullable
    public static Entity artilleryTarget() {
        if (artilleryEntityId < 0) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getEntity(artilleryEntityId);
    }

    /** True while the cam has something to follow: a tracked entity or fresh relay data. */
    private static boolean hasLiveArtilleryTarget() {
        if (artilleryGraceTicks > 0) return true;
        if (artilleryTarget() != null) return true;
        if (!artilleryGhostAlive) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null
            && mc.level.getGameTime() - artilleryGhostUpdateGameTime <= ARTILLERY_GHOST_TIMEOUT_TICKS;
    }

    /** Shell type key for the relay ghost model (empty while the real entity is tracked). */
    public static String artilleryGhostTypeKey() {
        return artilleryGhostTypeKey;
    }

    public static String entityTypeKey() {
        return entityTypeKey;
    }

    public static float currentSpeed() {
        return currentSpeed;
    }

    public static String statusLine() {
        return switch (mode) {
            case REPLAY -> {
                String base = outcomeSummary != null && !outcomeSummary.isEmpty() ? outcomeSummary : entityTypeKey;
                yield base + "  ·  " + String.format("%.2fx", currentSpeed);
            }
            case ARTILLERY -> "Artillery cam  ·  LIVE";
            case TERMINAL -> {
                // Merged burst shells show as a small (+N); the summary line is
                // the first shell's outcome, the critical list covers the rest.
                String s = "X-RAY  \u00b7  " + ClientTerminalView.summary();
                int extra = ClientTerminalView.actorCount() - 1;
                if (extra > 0) s += "  (+" + extra + ")";
                yield s;
            }
            default -> "";
        };
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (mode != Mode.NONE) stop();
            ArtilleryChunkCache.setActive(false);
            return;
        }
        updateTerrainCache(mc);
        // Live toggle-off: the client master switch closes any running
        // automatic view immediately (the replay view is user-initiated and
        // unaffected).
        if (mode == Mode.ARTILLERY || mode == Mode.TERMINAL) {
            if (!TBConfig.XRAY_VIEW_ENABLED.get()) {
                stop();
                return;
            }
        }
        if (artilleryGraceTicks > 0) artilleryGraceTicks--;
        switch (mode) {
            case REPLAY -> {
                if (playHead >= span && ++holdTicks >= HOLD_TICKS) {
                    stop();
                }
            }
            case ARTILLERY -> {
                if (!hasLiveArtilleryTarget()) {
                    // One-shot diagnostic: the tracked entity is despawned
                    // beyond tracking range and the relay went quiet, so say
                    // which half failed if the view ends mid-flight.
                    Minecraft mc2 = Minecraft.getInstance();
                    Entity target = artilleryTarget();
                    long silence = mc2.level != null && lastArtilleryRelayGameTime != Long.MIN_VALUE
                        ? mc2.level.getGameTime() - lastArtilleryRelayGameTime : -1L;
                    CBCTerminalBallistics.LOGGER.info(
                        "[CTB-HITCAM] artillery view ended: entity={}, ghostAlive={}, relaySilenceTicks={}",
                        target != null, artilleryGhostAlive, silence);
                    stop();
                }
            }
            case TERMINAL -> {
                // Parked packets play the moment the animation completes; run
                // this before the expiry check so a queued engagement is never
                // lost to the view timing out.
                ClientTerminalView.clientTick();
                if (ClientTerminalView.expired()) {
                    stop();
                }
            }
            default -> {
                ClientTerminalView.clientTick();
            }
        }
    }

    /**
     * Advances the replay playhead once per rendered frame using the real
     * frame delta, so slow motion stays smooth instead of stepping per tick.
     */
    public static void advance(float partialTick) {
        if (mode != Mode.REPLAY) return;
        if (lastPartialTick < 0.0F) lastPartialTick = partialTick;
        float delta = partialTick - lastPartialTick;
        if (delta < 0.0F) delta += 1.0F;
        lastPartialTick = partialTick;
        if (playHead >= span) {
            currentSpeed = 0.0F;
            return;
        }

        float endFraction = TBConfig.HITCAM_SLOWMO_END_FRACTION.get().floatValue();
        float minSpeed = TBConfig.HITCAM_SLOWMO_MIN_SPEED.get().floatValue();
        float baseSpeed = TBConfig.HITCAM_BASE_SPEED.get().floatValue();
        float progress = (float) (playHead / span);
        float speed = baseSpeed;
        if (endFraction > 1.0e-3F && progress > 1.0F - endFraction) {
            float t = (progress - (1.0F - endFraction)) / endFraction;
            t = t * t * (3.0F - 2.0F * t);
            speed = baseSpeed - (baseSpeed - minSpeed) * t;
        }
        currentSpeed = speed;
        playHead = Math.min(span, playHead + delta * speed);
    }

    public static Vec3 shellPos() {
        return shellPos(0.0F);
    }

    /**
     * Shell position interpolated to this rendered frame. Entities advance once
     * per server tick, so the chase camera and manual PiP shell render would
     * otherwise sit up to a block behind the shell the world pass drew at the
     * partial-tick position, pushing it toward the window edge. When the entity
     * is beyond tracking range, extrapolates the last server relay instead.
     */
    public static Vec3 shellPos(float partialTick) {
        if (isTerminal()) {
            Vec3 pos = ClientTerminalView.shellPos(partialTick);
            return pos != null ? pos : samplePos(playHead);
        }
        if (isArtillery()) {
            Entity target = artilleryTarget();
            if (target != null) {
                return lerpedPosition(target, partialTick);
            }
            return ghostExtrapolated(partialTick);
        }
        return samplePos(playHead);
    }

    /** Relay-based shell position, extrapolated forward by velocity since the last update. */
    private static Vec3 ghostExtrapolated(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        double elapsed = mc.level != null ? mc.level.getGameTime() - artilleryGhostUpdateGameTime : 0L;
        return artilleryGhostPos.add(artilleryGhostVel.scale(Math.max(0.0D, elapsed + partialTick)));
    }

    /** Partial-tick interpolated entity position, matching the vanilla render. */
    public static Vec3 lerpedPosition(Entity entity, float partialTick) {
        return new Vec3(
            Mth.lerp(partialTick, entity.xOld, entity.getX()),
            Mth.lerp(partialTick, entity.yOld, entity.getY()),
            Mth.lerp(partialTick, entity.zOld, entity.getZ()));
    }

    public static Vec3 shellDir() {
        return shellDir(0.0F);
    }

    public static Vec3 shellDir(float partialTick) {
        if (isTerminal()) {
            Vec3 dir = ClientTerminalView.shellDir(partialTick);
            return dir != null ? dir : sampleDir(playHead);
        }
        if (isArtillery()) {
            Entity target = artilleryTarget();
            Vec3 vel = target != null ? target.getDeltaMovement() : artilleryGhostVel;
            if (vel.lengthSqr() > 1.0e-6D) return vel.normalize();
            Vec3 look = target != null ? target.getLookAngle() : Vec3.ZERO;
            return look.lengthSqr() < 1.0e-6D ? new Vec3(0, 1, 0) : look.normalize();
        }
        return sampleDir(playHead);
    }

    public static Vec3 cameraPos(float partialTick) {
        if (isTerminal()) {
            return ClientTerminalView.cameraPos(partialTick);
        }
        Vec3 dir = shellDir();
        if (isArtillery()) {
            // Further 3/4 chase view: behind and to the side of the shell, so the
            // projectile model is clearly visible against the flight path.
            Vec3 pos = shellPos(partialTick);
            Vec3 side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0e-6D) side = new Vec3(1, 0, 0);
            side = side.normalize();
            return pos.subtract(dir.scale(10.5D)).add(0.0D, 3.5D, 0.0D).add(side.scale(2.5D));
        }
        return shellPos().subtract(dir.scale(4.5D)).add(0.0D, 1.2D, 0.0D);
    }

    public static Vec3 lookTarget(float partialTick) {
        if (isTerminal()) {
            // Track the action: the shell in flight, then the impact/spall site.
            return ClientTerminalView.lookPoint(partialTick);
        }
        if (isArtillery()) {
            // Lead the shell slightly so it sits below-center with the impact
            // area ahead, instead of filling the window dead-center.
            return shellPos(partialTick).add(shellDir().scale(3.0D));
        }
        // Look straight at the shell so it stays centered in the PiP window.
        return shellPos();
    }

    private static Vec3 samplePos(double t) {
        if (samples.isEmpty()) return Vec3.ZERO;
        int i0 = (int) Math.floor(t);
        i0 = Math.max(0, Math.min(i0, samples.size() - 1));
        int i1 = Math.min(i0 + 1, samples.size() - 1);
        double frac = Math.max(0.0D, Math.min(1.0D, t - i0));
        HitcamSample s0 = samples.get(i0);
        HitcamSample s1 = samples.get(i1);
        return new Vec3(
            s0.x() + (s1.x() - s0.x()) * frac,
            s0.y() + (s1.y() - s0.y()) * frac,
            s0.z() + (s1.z() - s0.z()) * frac);
    }

    private static Vec3 sampleDir(double t) {
        if (samples.isEmpty()) return new Vec3(0, 1, 0);
        int i0 = (int) Math.floor(t);
        i0 = Math.max(0, Math.min(i0, samples.size() - 1));
        int i1 = Math.min(i0 + 1, samples.size() - 1);
        double frac = Math.max(0.0D, Math.min(1.0D, t - i0));
        HitcamSample s0 = samples.get(i0);
        HitcamSample s1 = samples.get(i1);
        double x = s0.ox() + (s1.ox() - s0.ox()) * frac;
        double y = s0.oy() + (s1.oy() - s0.oy()) * frac;
        double z = s0.oz() + (s1.oz() - s0.oz()) * frac;
        Vec3 dir = new Vec3(x, y, z);
        return dir.lengthSqr() < 1.0e-6D ? new Vec3(0, 1, 0) : dir.normalize();
    }

    private ClientHitcam() {}
}
