package com.cbc_terminal_ballistics.debug;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TBDebug {
    public static final AtomicInteger GENERIC_MIXIN_HITS = new AtomicInteger();
    public static final AtomicInteger BIG_MIXIN_HITS = new AtomicInteger();
    public static final AtomicInteger AUTO_MIXIN_HITS = new AtomicInteger();
    public static final AtomicInteger OPTIONAL_MIXIN_HITS = new AtomicInteger();
    public static final AtomicInteger SERVICE_HITS = new AtomicInteger();
    public static final AtomicInteger SERVICE_FALLBACKS = new AtomicInteger();
    public static final AtomicInteger PENETRATES = new AtomicInteger();
    public static final AtomicInteger STOPS = new AtomicInteger();
    public static final AtomicInteger BOUNCES = new AtomicInteger();
    public static final AtomicLong LAST_HIT_TIME = new AtomicLong();
    public static volatile String LAST_PROJECTILE = "none";
    public static volatile String LAST_BLOCK = "none";
    public static volatile String LAST_OUTCOME = "none";
    public static volatile String LAST_ERROR = "none";

    private static final AtomicInteger VERBOSE_LINES = new AtomicInteger();

    public static void startupDiagnostics() {
        CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] CBC Terminal Ballistics DEBUG build is loaded");
        logMod("create");
        logMod("createbigcannons");
        logMod("ritchiesprojectilelib");
        logMod("cbcmoreshells");
        logMod("cbcmodernwarfare");
        logMod("rha");
        logMod("s_a_b");
        inspectClass("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile");
        inspectClass("rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile");
        inspectClass("rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile");
        inspectClass("rbasamoyai.ritchiesprojectilelib.mixin.ServerEntityMixin");
    }

    private static void logMod(String modid) {
        ModList.get().getModContainerById(modid).ifPresentOrElse(
            c -> CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] mod {} present version {}", modid, c.getModInfo().getVersion()),
            () -> CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] mod {} absent", modid));
    }

    public static String inspectClass(String name) {
        try {
            Class<?> cls = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            StringBuilder sb = new StringBuilder("present: ").append(cls.getName()).append(" methods=");
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().contains("calculateBlockPenetration") || m.getName().contains("clipAndDamage") || m.getName().contains("onImpact")) {
                    sb.append(m.getName()).append('(').append(m.getParameterCount()).append(") ");
                }
            }
            String out = sb.toString();
            CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] class {} {}", name, out);
            return out;
        } catch (Throwable t) {
            String out = "missing/error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] class {} {}", name, out);
            return out;
        }
    }

    public static void mixinHit(String type, Entity projectile, BlockState state, BlockPos pos) {
        if ("generic".equals(type)) GENERIC_MIXIN_HITS.incrementAndGet(); else if ("big".equals(type)) BIG_MIXIN_HITS.incrementAndGet(); else if ("autocannon".equals(type)) AUTO_MIXIN_HITS.incrementAndGet(); else OPTIONAL_MIXIN_HITS.incrementAndGet();
        LAST_PROJECTILE = projectile.getType().toString();
        LAST_BLOCK = state.getBlock().toString() + " @ " + pos.toShortString();
        LAST_HIT_TIME.set(System.currentTimeMillis());
        if (VERBOSE_LINES.getAndIncrement() < 200) {
            CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] {} mixin hit projectile={} block={} pos={}", type, LAST_PROJECTILE, state, pos.toShortString());
        }
    }

    public static void serviceOutcome(String outcome, double damage, double threshold) {
        SERVICE_HITS.incrementAndGet();
        LAST_OUTCOME = outcome + String.format(" damage=%.3f threshold=%.3f", damage, threshold);
        switch (outcome) {
            case "PENETRATE" -> PENETRATES.incrementAndGet();
            case "BOUNCE" -> BOUNCES.incrementAndGet();
            default -> STOPS.incrementAndGet();
        }
        if (VERBOSE_LINES.getAndIncrement() < 200) {
            CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] service outcome={} damage={} threshold={}", outcome, damage, threshold);
        }
    }

    public static void fallback(Throwable t) {
        SERVICE_FALLBACKS.incrementAndGet();
        LAST_ERROR = t.getClass().getName() + ": " + t.getMessage();
        CBCTerminalBallistics.LOGGER.error("[CTB-DEBUG] service fallback", t);
    }

    public static String statusLine() {
        return "genericMixin=" + GENERIC_MIXIN_HITS.get()
            + " bigMixin=" + BIG_MIXIN_HITS.get()
            + " autoMixin=" + AUTO_MIXIN_HITS.get()
            + " optionalMixin=" + OPTIONAL_MIXIN_HITS.get()
            + " service=" + SERVICE_HITS.get()
            + " fallbacks=" + SERVICE_FALLBACKS.get()
            + " outcomes[P/S/B]=" + PENETRATES.get() + "/" + STOPS.get() + "/" + BOUNCES.get()
            + " lastProjectile=" + LAST_PROJECTILE
            + " lastBlock=" + LAST_BLOCK
            + " lastOutcome=" + LAST_OUTCOME
            + " lastError=" + LAST_ERROR;
    }

    private TBDebug() {}
}
