package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Client-side playback of a recorded projectile flight. The live world keeps
 * ticking untouched; this only advances a virtual playhead over per-tick
 * samples. Slow motion is just a slower playhead rate, exactly like the
 * approach used by replay mods, minus the need to pause or slow the world.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientHitcam {
    private static final int HOLD_TICKS = 40;

    private static boolean active;
    private static String entityTypeKey = "";
    private static String outcomeSummary;
    private static List<HitcamSample> samples = List.of();
    private static double playHead;
    private static double span;
    private static int holdTicks;
    private static float lastPartialTick = -1.0F;
    private static float currentSpeed = 1.0F;

    public static void start(String typeKey, List<HitcamSample> recorded, String summary) {
        if (recorded.size() < 2) return;
        active = true;
        entityTypeKey = typeKey == null ? "" : typeKey;
        outcomeSummary = summary;
        samples = List.copyOf(recorded);
        span = samples.size() - 1;
        playHead = 0.0D;
        holdTicks = 0;
        lastPartialTick = -1.0F;
        currentSpeed = 1.0F;
    }

    public static void stop() {
        active = false;
        samples = List.of();
        outcomeSummary = null;
    }

    public static boolean active() {
        return active;
    }

    public static String entityTypeKey() {
        return entityTypeKey;
    }

    public static float currentSpeed() {
        return currentSpeed;
    }

    public static String statusLine() {
        String base = outcomeSummary != null && !outcomeSummary.isEmpty() ? outcomeSummary : entityTypeKey;
        return base + "  ·  " + String.format("%.2fx", currentSpeed);
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stop();
            return;
        }
        if (playHead >= span && ++holdTicks >= HOLD_TICKS) {
            stop();
        }
    }

    /**
     * Advances the playhead once per rendered frame using the real frame
     * delta, so slow motion stays smooth instead of stepping per tick.
     */
    public static void advance(float partialTick) {
        if (!active) return;
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
        float progress = (float) (playHead / span);
        float speed = 1.0F;
        if (endFraction > 1.0e-3F && progress > 1.0F - endFraction) {
            float t = (progress - (1.0F - endFraction)) / endFraction;
            t = t * t * (3.0F - 2.0F * t);
            speed = 1.0F - (1.0F - minSpeed) * t;
        }
        currentSpeed = speed;
        playHead = Math.min(span, playHead + delta * speed);
    }

    public static Vec3 shellPos() {
        return samplePos(playHead);
    }

    public static Vec3 shellDir() {
        return sampleDir(playHead);
    }

    public static Vec3 cameraPos() {
        Vec3 dir = shellDir();
        return shellPos().subtract(dir.scale(4.5D)).add(0.0D, 1.2D, 0.0D);
    }

    public static Vec3 lookTarget() {
        // Look straight at the shell so it stays centered in the PiP window.
        return shellPos();
    }

    private static Vec3 samplePos(double t) {
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
