package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import net.minecraft.client.Minecraft;
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
        mode = Mode.ARTILLERY;
        artilleryEntityId = entityId;
        ClientTerminalView.clear();
        resetReplay();
    }

    /** Terminal view packet arrived; called by {@link ClientTerminalView#accept}. */
    public static void startTerminalView() {
        mode = Mode.TERMINAL;
        artilleryEntityId = -1;
        resetReplay();
    }

    public static void stop() {
        mode = Mode.NONE;
        artilleryEntityId = -1;
        ClientTerminalView.clear();
        resetReplay();
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
            case ARTILLERY -> artilleryTarget() != null;
            case TERMINAL -> ClientTerminalView.active();
            default -> false;
        };
    }

    public static boolean isReplay() {
        return mode == Mode.REPLAY;
    }

    public static boolean isArtillery() {
        return mode == Mode.ARTILLERY && artilleryTarget() != null;
    }

    public static boolean isTerminal() {
        return mode == Mode.TERMINAL && ClientTerminalView.active();
    }

    /** The live projectile entity the artillery cam is following, or null. */
    public static Entity artilleryTarget() {
        if (artilleryEntityId < 0) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        return mc.level.getEntity(artilleryEntityId);
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
            case TERMINAL -> "X-RAY  ·  " + ClientTerminalView.summary();
            default -> "";
        };
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            if (mode != Mode.NONE) stop();
            return;
        }
        switch (mode) {
            case REPLAY -> {
                if (playHead >= span && ++holdTicks >= HOLD_TICKS) {
                    stop();
                }
            }
            case ARTILLERY -> {
                if (artilleryTarget() == null) {
                    stop();
                }
            }
            case TERMINAL -> {
                if (ClientTerminalView.expired()) {
                    stop();
                }
            }
            default -> {}
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
        if (isArtillery()) {
            Entity target = artilleryTarget();
            return target.position();
        }
        return samplePos(playHead);
    }

    public static Vec3 shellDir() {
        if (isArtillery()) {
            Entity target = artilleryTarget();
            Vec3 vel = target.getDeltaMovement();
            if (vel.lengthSqr() > 1.0e-6D) return vel.normalize();
            Vec3 look = target.getLookAngle();
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
            Vec3 pos = shellPos();
            Vec3 side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1.0e-6D) side = new Vec3(1, 0, 0);
            side = side.normalize();
            return pos.subtract(dir.scale(7.5D)).add(0.0D, 2.5D, 0.0D).add(side.scale(2.0D));
        }
        return shellPos().subtract(dir.scale(4.5D)).add(0.0D, 1.2D, 0.0D);
    }

    public static Vec3 lookTarget(float partialTick) {
        if (isTerminal()) {
            return ClientTerminalView.center(partialTick);
        }
        if (isArtillery()) {
            // Lead the shell slightly so it sits below-center with the impact
            // area ahead, instead of filling the window dead-center.
            return shellPos().add(shellDir().scale(3.0D));
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
