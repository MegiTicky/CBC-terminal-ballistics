package com.cbc_terminal_ballistics.debug;

import com.cbc_terminal_ballistics.network.ClientboundShellHitDebugPacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Server side of the {@code /cbctb shelldebug} overlay: while the window is
 * open, every cannon projectile decision near a contraption (miss, skip,
 * processed outcome, jam termination) is reported to the toggling player as a
 * HUD entry + world marker, so "should have hit but didn't" cases name their
 * own reason.
 */
public final class ShellHitDebug {
    private static final Logger LOG = LoggerFactory.getLogger("CBCTB/ShellHitDebug");
    private static volatile long activeUntilMillis = 0L;
    private static volatile ServerPlayer viewer;
    /** One record per shell per tick — the decision flow can fire several times per tick. */
    private static final WeakHashMap<Entity, long[]> LAST_TICK = new WeakHashMap<>();

    /** {@code /cbctb shelldebug} — opens the overlay window for this player. */
    public static void toggle(ServerPlayer player, int seconds) {
        viewer = player;
        activeUntilMillis = System.currentTimeMillis() + seconds * 1000L;
        LAST_TICK.clear();
    }

    public static boolean active() {
        return System.currentTimeMillis() < activeUntilMillis;
    }

    // Set by the state-substitution mixin so the immediately following
    // calculate() knows it is processing a substituted contraption hit.
    private static volatile boolean substitutedPending;

    public static void markSubstituted() {
        substitutedPending = true;
    }

    /** Get-and-clear. */
    public static boolean consumeSubstituted() {
        boolean v = substitutedPending;
        substitutedPending = false;
        return v;
    }

    /**
     * Report one decision for this shell. At most one packet per shell per game
     * tick, except high-priority verdicts (OUTCOME/JAM) which always record.
     */
    public static void record(Entity projectile, String verdict, String detail, Vec3 pos) {
        if (!active()) return;
        long tick = projectile.level().getGameTime();
        long[] last = LAST_TICK.get(projectile);
        boolean priority = verdict.startsWith("OUTCOME") || verdict.startsWith("JAM");
        if (last != null && last[0] == tick && !priority) return;
        LAST_TICK.put(projectile, new long[]{tick});
        ServerPlayer target = viewer;
        if (target == null || !target.isAlive()) return;
        try {
            ClientboundShellHitDebugPacket packet = new ClientboundShellHitDebugPacket(
                pos.x, pos.y, pos.z,
                projectile.getType().toShortString(),
                verdict,
                detail == null ? "" : detail);
            TBNetwork.CHANNEL.sendTo(packet, target.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        } catch (Throwable t) {
            LOG.debug("[ctb] shelldebug send failed: {}", t.getMessage());
        }
    }

    /**
     * The kinematic outcome (PENETRATE/STOP/RICOCHET/...) of a CBC
     * {@code ImpactResult}, or a best-effort fallback label.
     */
    public static String outcomeOf(@Nullable Object impactResult) {
        if (impactResult == null) return "null";
        try {
            for (Method m : impactResult.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!m.getName().toLowerCase().contains("outcome")) continue;
                if (Enum.class.isAssignableFrom(m.getReturnType())) {
                    return ((Enum<?>) m.invoke(impactResult)).name();
                }
            }
            for (Field f : impactResult.getClass().getDeclaredFields()) {
                if (!Enum.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(impactResult);
                if (v instanceof Enum<?> e) return e.name();
            }
        } catch (Throwable ignored) {
        }
        return impactResult.getClass().getSimpleName();
    }

    private ShellHitDebug() {}
}
