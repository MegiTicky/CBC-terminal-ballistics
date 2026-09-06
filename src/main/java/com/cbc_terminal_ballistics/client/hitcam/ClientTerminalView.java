package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.client.VSClientCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client state for the terminal-ballistics hitcam: the x-ray diagram of the
 * last shell-vs-armour engagement. Points arrive in vehicle-local space when
 * the shell hit a vehicle (contraption-local for Create contraptions,
 * shipyard-space for Valkyrien ships), so both the diagram and the orbit
 * camera around it follow the moving vessel.
 *
 * <p>The view plays back as an animation: the actual shell model (a ghost
 * entity of the recorded type) flies in along the recorded path, plates flash
 * as it passes them, then the spall cone grows and its victims light up as the
 * fragment front passes. {@link Timeline} is the per-frame snapshot the
 * overlay and PiP renderer consume.
 */
public final class ClientTerminalView {
    /** Fraction of the view duration spent turning; the rest holds the framing. */
    private static final double SWEEP_END_FRACTION = 0.8D;
    /** Total camera turn over the sweep, radians (~115 degrees). */
    private static final double SWEEP_RADIANS = 2.0D;
    /**
     * Starting offset of the sweep from the incoming-shell axis, radians (~46
     * degrees). Starting exactly on the axis looks straight down the trajectory:
     * the shell recedes instead of approaching, the look direction barely yaws,
     * and every stroke is end-on (invisible) until the sweep opens.
     */
    private static final double SWEEP_START_OFFSET = 0.8D;
    /** Replay speed: flight duration in ticks per block of recorded path. */
    private static final double FLIGHT_TICKS_PER_BLOCK = 0.6D;
    private static final double FLIGHT_MIN_TICKS = 20.0D;
    private static final double FLIGHT_MAX_TICKS = 40.0D;
    /** Ticks for the spall cone to reach its full extent. */
    private static final double SPALL_GROW_TICKS = 14.0D;
    /** Brightness flash duration when a plate is struck. */
    private static final double PLATE_FLASH_TICKS = 8.0D;
    /** Ticks the shell model lingers after reaching the end of the path. */
    private static final float SHELL_FADE_TICKS = 4.0F;
    /** Time-warp LUT resolution for the bullet-time flight easing. */
    private static final int LUT_STEPS = 120;
    /** Ticks over which the camera eases into the post-impact close-up. */
    private static final double CLOSEUP_TICKS = 20.0D;
    /** Fraction of the orbit radius removed at the closest point of the close-up. */
    private static final double CLOSEUP_FRACTION = 0.28D;

    private static ClientboundTerminalBallisticsPacket view;
    private static long startTick;
    /**
     * Tick (relative to the current view's start) at which the active animation
     * — shell flight, plate flashes, spall growth — has fully played. Until
     * then the view is sticky: packets about other shells and mid-flight
     * restarts wait in {@link #pending} instead of cutting the animation.
     */
    private static double animationEndTicks = 20.0D;
    /** Newest packet parked while the current animation plays; plays when it ends. */
    private static ClientboundTerminalBallisticsPacket pending;

    // Anchor: null kind = world space, CONTRAPTION = Create entity by UUID,
    // VS = Valkyrien ship identified by localRef (shipyard) + worldRef (world).
    private static String anchorKind;
    private static UUID contraptionUuid;
    private static Entity anchorEntity;
    private static Vec3 vsLocalRef = Vec3.ZERO;
    private static Vec3 vsWorldRef = Vec3.ZERO;
    private static Vec3 vsOffset = Vec3.ZERO;
    private static Object vsShip;

    private static Vec3 anchorCenter = Vec3.ZERO;
    private static double orbitRadius = 8.0D;

    // Animation timeline, precomputed once in local space.
    private static boolean hasPath;
    private static List<HitcamSample> trimmedPath = List.of();
    private static double[] pathCumLen;
    private static double totalLen;
    private static double flightTicks;
    private static double impactTime;
    private static double[] timeLut;
    private static Vec3 incomingDirLocal = Vec3.ZERO;
    private static double[] plateAppear;
    private static double spallAppear;
    private static double[] blockDistFrac;
    private static double[] entityAppear;

    // Ghost shell model for the replay.
    private static Entity ghostShell;
    private static boolean ghostFailed;
    private static String ghostKeyUsed = "";

    /** Per-frame animation snapshot consumed by the overlay and PiP renderer. */
    public static final class Timeline {
        public final float age;
        public final float fade;
        public final boolean hasPath;
        public final double flightTicks;
        public final double shellProgress;
        @Nullable public final Vec3 shellPos;
        public final Vec3 shellDir;
        /** 0 once the shell has passed the end of its path (plus a short linger). */
        public final float shellAlpha;
        public final double[] plateAppear;
        public final double spallStart;
        public final float spallGrowth;
        public final double[] blockDistFrac;
        public final double[] entityAppear;

        Timeline(float age, float fade, boolean hasPath, double flightTicks, double shellProgress,
                 @Nullable Vec3 shellPos, Vec3 shellDir, float shellAlpha,
                 double[] plateAppear, double spallStart, float spallGrowth,
                 double[] blockDistFrac, double[] entityAppear) {
            this.age = age;
            this.fade = fade;
            this.hasPath = hasPath;
            this.flightTicks = flightTicks;
            this.shellProgress = shellProgress;
            this.shellPos = shellPos;
            this.shellDir = shellDir;
            this.shellAlpha = shellAlpha;
            this.plateAppear = plateAppear;
            this.spallStart = spallStart;
            this.spallGrowth = spallGrowth;
            this.blockDistFrac = blockDistFrac;
            this.entityAppear = entityAppear;
        }

        /** 0..1 brightness boost right after a plate is struck. */
        public float plateFlash(int index) {
            if (plateAppear == null || index >= plateAppear.length) return 0.0F;
            float since = age - (float) plateAppear[index];
            if (since < 0.0F) return 0.0F;
            return Mth.clamp(1.0F - since / (float) PLATE_FLASH_TICKS, 0.0F, 1.0F);
        }
    }

    public static void accept(ClientboundTerminalBallisticsPacket packet) {
        if (view != null && ageTicks() < duration()) {
            double age = ageTicks();
            boolean sameShell = view.projectileId() == packet.projectileId();
            // A finalize that lands while the first-hit animation has barely
            // started (shell stopped on the next plate a tick or two later)
            // replaces it outright — the stub is invisible. Anything else that
            // would cut a playing animation parks until the animation ends.
            if (sameShell && (age < 10.0D || age >= animationEndTicks)) {
                start(packet, "replace same-shell stub");
                return;
            }
            if (!sameShell && age >= animationEndTicks) {
                start(packet, "previous animation finished");
                return;
            }
            pending = packet;
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-HITCAM] parked terminal packet shell={} summary=\"{}\" plates={} (view shell={} age={} animEnd={})",
                packet.projectileId(), packet.summary(), packet.plates().size(),
                view.projectileId(), age, animationEndTicks);
            return;
        }
        start(packet, "no active view");
    }

    private static void start(ClientboundTerminalBallisticsPacket packet, String reason) {
        pending = null;
        view = packet;
        ClientboundTerminalBallisticsPacket.Anchor anchor = packet.anchor();
        anchorKind = anchor == null ? null : anchor.kind();
        contraptionUuid = null;
        anchorEntity = null;
        vsShip = null;
        if (anchor != null && "CONTRAPTION".equals(anchor.kind()) && anchor.uuid() != null) {
            contraptionUuid = UUID.fromString(anchor.uuid());
        } else if (anchor != null && "VS".equals(anchor.kind())
            && anchor.localRef() != null && anchor.worldRef() != null) {
            vsLocalRef = anchor.localRef();
            vsWorldRef = anchor.worldRef();
            vsOffset = vsWorldRef.subtract(vsLocalRef);
        }
        Minecraft mc = Minecraft.getInstance();
        startTick = mc.level != null ? mc.level.getGameTime() : 0L;
        recomputeBounds();
        buildTimeline();
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] terminal view {} shell={} pathPts={} trimmed={} flight={} impact={} animEnd={} anchor={} ship={}",
            reason, packet.projectileId(), packet.path().size(), trimmedPath.size(),
            String.format("%.1f", flightTicks), String.format("%.1f", impactTime),
            String.format("%.1f", animationEndTicks), anchorKind, vsShip != null);
        ClientHitcam.startTerminalView();
    }

    public static void clear() {
        view = null;
        anchorEntity = null;
        anchorKind = null;
        contraptionUuid = null;
        vsShip = null;
        ghostShell = null;
        ghostKeyUsed = "";
        // {@link #pending} deliberately survives: it is flushed by
        // {@link #clientTick()} the moment the current view is gone.
    }

    /** True while a terminal animation is actively playing (not yet a static hold). */
    public static boolean animationInProgress() {
        return view != null && ageTicks() < animationEndTicks;
    }

    /** Plays a parked packet once the current animation has finished. Call once per client tick. */
    public static void clientTick() {
        if (pending == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            pending = null;
            return;
        }
        if (view == null || ageTicks() >= animationEndTicks || ageTicks() >= duration()) {
            ClientboundTerminalBallisticsPacket packet = pending;
            pending = null;
            CBCTerminalBallistics.LOGGER.info("[CTB-HITCAM] playing parked terminal packet shell={} plates={}",
                packet.projectileId(), packet.plates().size());
            accept(packet);
        }
    }

    public static boolean active() {
        return view != null;
    }

    public static boolean expired() {
        return view == null || ageTicks() >= duration();
    }

    public static int duration() {
        return Math.max(20, TBConfig.HITCAM_TERMINAL_DURATION.get());
    }

    public static String summary() {
        return view == null ? "" : view.summary();
    }

    public static String entityTypeKey() {
        return view == null ? "" : view.entityTypeKey();
    }

    public static float age(float partialTick) {
        return ageTicks() + partialTick;
    }

    private static long ageTicks() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime() - startTick;
    }

    public static List<HitcamSample> path() {
        return view == null ? List.of() : view.path();
    }

    public static List<ClientboundTerminalBallisticsPacket.Plate> plates() {
        return view == null ? List.of() : view.plates();
    }

    @Nullable
    public static ClientboundTerminalBallisticsPacket.Spall spall() {
        return view == null ? null : view.spall();
    }

    public static List<ClientboundTerminalBallisticsPacket.EntityHit> entities() {
        return view == null ? List.of() : view.entities();
    }

    /** Vehicle-local -> world for any stored point; identity without an anchor. */
    public static Vec3 toWorld(Vec3 local, float partialTick) {
        Entity anchor = contraptionAnchor();
        if (anchor != null) {
            Vec3 world = ContraptionCompat.toGlobalVector(anchor, local, partialTick);
            return world != null ? world : local;
        }
        if ("VS".equals(anchorKind)) {
            Object ship = vsShip();
            Vec3 world = ship != null ? VSClientCompat.shipyardToWorld(ship, local) : null;
            if (world != null) return world;
            // Ship not resolvable right now (unloaded/too far): keep the diagram
            // near the impact point via a static offset instead of vanishing.
            return local.add(vsOffset);
        }
        return local;
    }

    /** Vehicle-local direction -> world (rotation only), via a two-point transform. */
    public static Vec3 toWorldDir(Vec3 localDir, float partialTick) {
        Vec3 from = toWorld(Vec3.ZERO, partialTick);
        Vec3 to = toWorld(localDir, partialTick);
        Vec3 dir = to.subtract(from);
        return dir.lengthSqr() < 1.0e-8D ? localDir : dir.normalize();
    }

    /** Diagram center (orbit target) in world space. */
    public static Vec3 center(float partialTick) {
        return toWorld(anchorCenter, partialTick);
    }

    /**
     * The point the camera watches: the animated replay shell while it flies
     * in, but only up to the impact point — a pass-through shell continuing out
     * the far side does not drag the view with it. From impact on, the look
     * settles onto the terminal focus (spall origin if the plate spalled, else
     * the final plate position) to follow the spall progression.
     */
    public static Vec3 lookPoint(float partialTick) {
        Vec3 focus = terminalFocus(partialTick);
        if (!hasPath || flightTicks <= 0.0D) return focus;
        float age = age(partialTick);
        double impactFraction = Mth.clamp(impactTime / flightTicks, 0.0D, 1.0D);
        double progress = Math.min(progressAtTime(age / flightTicks), impactFraction);
        Vec3 follow = toWorld(pathPointAt(progress), partialTick);
        if (age >= impactTime) {
            double blend = Mth.clamp((age - impactTime) / 10.0D, 0.0D, 1.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            return follow.lerp(focus, blend);
        }
        return follow;
    }

    /** Where the camera settles after the shell lands: spall site, final plate, or center. */
    private static Vec3 terminalFocus(float partialTick) {
        ClientboundTerminalBallisticsPacket.Spall spall = spall();
        if (spall != null) return toWorld(spall.origin(), partialTick);
        List<ClientboundTerminalBallisticsPacket.Plate> plateList = plates();
        if (!plateList.isEmpty()) {
            return toWorld(plateList.get(plateList.size() - 1).pos(), partialTick);
        }
        return center(partialTick);
    }

    /**
     * Cinematic camera: a slow, eased ~115 degree turn starting on the side the
     * shell came from (looking at the impact face) and settling into a side
     * framing, orbited around the static diagram center while the look target
     * ({@link #lookPoint}) tracks the replay action. Always re-anchored to the
     * vehicle so a moving tank stays framed.
     */
    public static Vec3 cameraPos(float partialTick) {
        Vec3 c = center(partialTick);
        double age = age(partialTick);
        double sweepEnd = Math.max(1.0D, duration() * SWEEP_END_FRACTION);
        double t = Mth.clamp(age / sweepEnd, 0.0D, 1.0D);
        double eased = t * t * (3.0D - 2.0D * t);
        double base = incomingAzimuth(partialTick);
        double angle = base + SWEEP_START_OFFSET + eased * (SWEEP_RADIANS - SWEEP_START_OFFSET);
        // War-Thunder close-up: from impact on, the orbit eases in toward the
        // penetration point while the x-ray and spall play out.
        double r = orbitRadius;
        if (impactTime > 0.0D && age >= impactTime) {
            double blend = Mth.clamp((age - impactTime) / CLOSEUP_TICKS, 0.0D, 1.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            r *= 1.0D - CLOSEUP_FRACTION * blend;
        }
        return new Vec3(c.x + Math.cos(angle) * r, c.y + r * 0.5D, c.z + Math.sin(angle) * r);
    }

    /** World azimuth on the approach side of the recorded incoming direction, so the sweep starts face-on to the impact face. */
    private static double incomingAzimuth(float partialTick) {
        if (incomingDirLocal.lengthSqr() < 1.0e-8D) return Math.PI * 0.25D;
        Vec3 dir = toWorldDir(incomingDirLocal, partialTick);
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horiz < 1.0e-6D) return Math.PI * 0.25D;
        // Negated: the camera must sit behind the incoming shell (approach
        // side) to look at the impact face. Using the travel direction put it
        // on the far side, starting at the exit face and sweeping TO the face.
        return Math.atan2(dir.z, dir.x) + Math.PI;
    }

    // ---- animation timeline ------------------------------------------------------

    private static void buildTimeline() {
        trimmedPath = trimPath(path());
        hasPath = trimmedPath.size() >= 2;
        flightTicks = 0.0D;
        totalLen = 0.0D;
        pathCumLen = null;
        impactTime = 0.0D;
        timeLut = null;
        incomingDirLocal = Vec3.ZERO;
        if (hasPath) {
            pathCumLen = new double[trimmedPath.size()];
            double acc = 0.0D;
            for (int i = 1; i < trimmedPath.size(); i++) {
                acc += samplePoint(trimmedPath.get(i - 1)).distanceTo(samplePoint(trimmedPath.get(i)));
                pathCumLen[i] = acc;
            }
            totalLen = acc;
            flightTicks = Mth.clamp(totalLen * FLIGHT_TICKS_PER_BLOCK, FLIGHT_MIN_TICKS, FLIGHT_MAX_TICKS);
            Vec3 dirIn = samplePoint(trimmedPath.get(trimmedPath.size() - 1)).subtract(samplePoint(trimmedPath.get(0)));
            if (dirIn.lengthSqr() > 1.0e-8D) incomingDirLocal = dirIn.normalize();
            buildTimeLut();
        }

        List<ClientboundTerminalBallisticsPacket.Plate> plateList = plates();
        plateAppear = new double[plateList.size()];
        for (int i = 0; i < plateList.size(); i++) {
            plateAppear[i] = hasPath ? flightTicks * timeAtProgress(fractionOf(plateList.get(i).pos())) : 0.0D;
        }

        ClientboundTerminalBallisticsPacket.Spall spall = spall();
        spallAppear = 0.0D;
        blockDistFrac = null;
        if (spall != null) {
            double start = hasPath ? flightTicks * timeAtProgress(fractionOf(spall.origin())) : 0.0D;
            double lastPlate = plateAppear.length > 0 ? plateAppear[plateAppear.length - 1] : 0.0D;
            // The spall waits for the struck plate's flash to finish, so the
            // beats read in order: shell travels, block highlights, spall travels.
            spallAppear = Math.max(start, lastPlate + PLATE_FLASH_TICKS + 2.0D);
            blockDistFrac = new double[spall.blocks().size()];
            double range = Math.max(spall.range(), 0.5D);
            for (int i = 0; i < spall.blocks().size(); i++) {
                blockDistFrac[i] = Mth.clamp(spall.blocks().get(i).pos().distanceTo(spall.origin()) / range, 0.0D, 1.0D);
            }
        }
        // The impact moment (camera close-up start, look easing) is when the
        // shell lands on the last plate — not the delayed spall start.
        impactTime = plateAppear.length > 0 ? plateAppear[plateAppear.length - 1]
            : (spall != null ? spallAppear : (hasPath ? flightTicks : 0.0D));

        List<ClientboundTerminalBallisticsPacket.EntityHit> entityList = entities();
        entityAppear = new double[entityList.size()];
        for (int i = 0; i < entityList.size(); i++) {
            ClientboundTerminalBallisticsPacket.EntityHit hit = entityList.get(i);
            double direct = hasPath ? flightTicks * timeAtProgress(fractionOf(hit.pos())) : 0.0D;
            double viaSpall = 0.0D;
            if (spall != null) {
                double range = Math.max(spall.range(), 0.5D);
                viaSpall = spallAppear + SPALL_GROW_TICKS
                    * Mth.clamp(hit.pos().distanceTo(spall.origin()) / range, 0.0D, 1.0D);
            }
            entityAppear[i] = Math.max(direct, viaSpall);
        }

        // Tick at which the animation has fully played: last plate flashed,
        // spall fully grown, entity pops done. Until then the view is sticky.
        double end = 0.0D;
        if (spall != null) end = Math.max(end, spallAppear + SPALL_GROW_TICKS);
        for (double a : entityAppear) end = Math.max(end, a + 8.0D);
        if (plateAppear.length > 0) {
            end = Math.max(end, plateAppear[plateAppear.length - 1] + PLATE_FLASH_TICKS);
        }
        if (!hasPath) end = Math.max(end, 30.0D); // static diagram still deserves a beat
        animationEndTicks = Math.max(end + 4.0D, 10.0D);
    }

    /**
     * Final N blocks of the recorded flight (config {@code hitcamTerminalPathBlocks}):
     * long shots trim their approach so the replay and the slow-motion impact
     * dominate, War-Thunder style, instead of spending the whole flight budget
     * crossing empty sky. Always keeps the last sample and at least two points.
     */
    private static List<HitcamSample> trimPath(List<HitcamSample> recorded) {
        if (recorded.size() < 2) return recorded;
        double budget = Math.max(10.0D, TBConfig.HITCAM_TERMINAL_PATH_BLOCKS.get());
        int start = recorded.size() - 1;
        double acc = 0.0D;
        while (start > 0) {
            double seg = samplePoint(recorded.get(start - 1)).distanceTo(samplePoint(recorded.get(start)));
            if (acc + seg > budget && recorded.size() - start >= 2) break;
            acc += seg;
            start--;
        }
        return recorded.subList(start, recorded.size());
    }

    /**
     * Bullet-time curve for the flight, the same shape as the manual replay's
     * impact slow-down: full speed for the first {@code 1 - f} of the path,
     * smoothstep deceleration to the slow-mo speed over the final {@code f}.
     * The integral is normalized so the shell still covers the whole path in
     * exactly {@code flightTicks}.
     */
    private static void buildTimeLut() {
        double f = Mth.clamp(TBConfig.HITCAM_SLOWMO_END_FRACTION.get(), 0.0D, 1.0D);
        double m = Mth.clamp(TBConfig.HITCAM_SLOWMO_MIN_SPEED.get(), 0.01D, 1.0D);
        timeLut = new double[LUT_STEPS + 1];
        // ∫ speed over the whole flight; the decel segment averages (1+m)/2.
        double norm = f > 0.0D ? (1.0D - f) + f * (1.0D + m) * 0.5D : 1.0D;
        for (int i = 0; i <= LUT_STEPS; i++) {
            double u = (double) i / LUT_STEPS;
            double raw;
            if (f <= 0.0D || u <= 1.0D - f) {
                raw = u;
            } else {
                double t = (u - (1.0D - f)) / f;
                double ts = t * t * (3.0D - 2.0D * t);
                raw = (1.0D - f) + f * (t - (1.0D - m) * (t * t * t - t * t * t * t * 0.5D));
            }
            timeLut[i] = raw / norm;
        }
        timeLut[LUT_STEPS] = 1.0D;
    }

    /** Path progress 0..1 the shell has reached at normalized flight time {@code u} 0..1. */
    private static double progressAtTime(double u) {
        if (timeLut == null) return Mth.clamp(u, 0.0D, 1.0D);
        u = Mth.clamp(u, 0.0D, 1.0D);
        double x = u * LUT_STEPS;
        int i = Math.min((int) x, LUT_STEPS - 1);
        return Mth.lerp(x - i, timeLut[i], timeLut[i + 1]);
    }

    /** Normalized flight time 0..1 at which the shell reaches path progress {@code p} 0..1. */
    private static double timeAtProgress(double p) {
        if (timeLut == null) return Mth.clamp(p, 0.0D, 1.0D);
        p = Mth.clamp(p, 0.0D, 1.0D);
        for (int i = 1; i <= LUT_STEPS; i++) {
            if (timeLut[i] >= p) {
                double p0 = timeLut[i - 1];
                double p1 = timeLut[i];
                double frac = p1 > p0 ? (p - p0) / (p1 - p0) : 0.0D;
                return ((i - 1) + frac) / LUT_STEPS;
            }
        }
        return 1.0D;
    }

    /** Recorded path fraction (0..1) of a local point: nearest path sample. */
    private static double fractionOf(Vec3 local) {
        if (!hasPath || totalLen < 1.0e-6D) return 0.0D;
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < trimmedPath.size(); i++) {
            double d = samplePoint(trimmedPath.get(i)).distanceToSqr(local);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return pathCumLen[best] / totalLen;
    }

    /** Local path point at progress 0..1, linearly interpolated along the polyline. */
    public static Vec3 pathPointAt(double progress) {
        if (trimmedPath.isEmpty()) return Vec3.ZERO;
        if (trimmedPath.size() == 1 || totalLen < 1.0e-6D) return samplePoint(trimmedPath.get(0));
        double target = Mth.clamp(progress, 0.0D, 1.0D) * totalLen;
        for (int i = 1; i < trimmedPath.size(); i++) {
            if (pathCumLen[i] >= target) {
                double segLen = pathCumLen[i] - pathCumLen[i - 1];
                double frac = segLen < 1.0e-6D ? 0.0D : (target - pathCumLen[i - 1]) / segLen;
                return samplePoint(trimmedPath.get(i - 1)).lerp(samplePoint(trimmedPath.get(i)), frac);
            }
        }
        return samplePoint(trimmedPath.get(trimmedPath.size() - 1));
    }

    /** Local path direction at progress 0..1 (segment direction). */
    public static Vec3 pathDirAt(double progress) {
        if (trimmedPath.size() < 2) return incomingDirLocal;
        double target = Mth.clamp(progress, 0.0D, 1.0D) * totalLen;
        for (int i = 1; i < trimmedPath.size(); i++) {
            if (pathCumLen[i] >= target) {
                Vec3 dir = samplePoint(trimmedPath.get(i)).subtract(samplePoint(trimmedPath.get(i - 1)));
                return dir.lengthSqr() < 1.0e-8D ? incomingDirLocal : dir.normalize();
            }
        }
        return incomingDirLocal;
    }

    /** Cumulative path fraction of sample {@code index} (for progressive drawing). */
    public static double pathFraction(int index) {
        if (!hasPath || totalLen < 1.0e-6D) return 0.0D;
        int i = Mth.clamp(index, 0, pathCumLen.length - 1);
        return pathCumLen[i] / totalLen;
    }

    public static int pathPointCount() {
        return trimmedPath.size();
    }

    /** Local position of path sample {@code index}. */
    public static Vec3 pathPoint(int index) {
        return trimmedPath.isEmpty() ? Vec3.ZERO
            : samplePoint(trimmedPath.get(Mth.clamp(index, 0, trimmedPath.size() - 1)));
    }

    private static Vec3 samplePoint(HitcamSample sample) {
        return new Vec3(sample.x(), sample.y(), sample.z());
    }

    /** Per-frame animation state. */
    public static Timeline snapshot(float partialTick) {
        float age = age(partialTick);
        float remaining = duration() - age;
        float fade = Mth.clamp(remaining / 20.0F, 0.0F, 1.0F);
        if (!hasPath) {
            return new Timeline(age, fade, false, 0.0D, 1.0D, null, Vec3.ZERO, 0.0F,
                plateAppear, spallAppear, 1.0F, blockDistFrac, entityAppear);
        }
        double progress = progressAtTime(age / flightTicks);
        Vec3 local = pathPointAt(progress);
        Vec3 world = toWorld(local, partialTick);
        Vec3 dir = toWorldDir(pathDirAt(progress), partialTick);
        float linger = 1.0F - (float) Mth.clamp((age - flightTicks) / SHELL_FADE_TICKS, 0.0D, 1.0D);
        float growth = (float) Mth.clamp((age - spallAppear) / SPALL_GROW_TICKS, 0.0D, 1.0D);
        return new Timeline(age, fade, true, flightTicks, progress, world, dir, linger,
            plateAppear, spallAppear, growth, blockDistFrac, entityAppear);
    }

    /** World-space shell position for the current animation frame, or null when hidden. */
    @Nullable
    public static Vec3 shellPos(float partialTick) {
        return snapshot(partialTick).shellPos;
    }

    /** World-space shell direction for the current animation frame. */
    public static Vec3 shellDir(float partialTick) {
        return snapshot(partialTick).shellDir;
    }

    /**
     * The ghost shell model for the replay, positioned and oriented for this
     * frame. Returns null once the shell has passed the end of the path, or if
     * the entity type cannot be resolved (the caller falls back to a streak).
     */
    @Nullable
    public static Entity ghost(float partialTick) {
        Timeline t = snapshot(partialTick);
        if (t.shellAlpha <= 0.05F || t.shellPos == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String key = entityTypeKey();
        if (key.isEmpty()) return null;
        if ((ghostShell == null && !ghostFailed) || !key.equals(ghostKeyUsed)) {
            ghostKeyUsed = key;
            Optional<EntityType<?>> type = EntityType.byString(key);
            if (type.isPresent()) {
                try {
                    ghostShell = type.get().create(mc.level);
                } catch (Throwable ex) {
                    ghostShell = null;
                }
            }
            if (ghostShell == null) ghostFailed = true;
        }
        if (ghostShell == null) return null;
        ghostShell.setPos(t.shellPos.x, t.shellPos.y, t.shellPos.z);
        ghostShell.xOld = ghostShell.xo = t.shellPos.x;
        ghostShell.yOld = ghostShell.yo = t.shellPos.y;
        ghostShell.zOld = ghostShell.zo = t.shellPos.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-t.shellDir.x, t.shellDir.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(t.shellDir.y, -1.0D, 1.0D)));
        ghostShell.setYRot(yaw);
        ghostShell.setXRot(pitch);
        ghostShell.yRotO = yaw;
        ghostShell.xRotO = pitch;
        ClientHitcamPiP.setGhostOrientation(ghostShell, t.shellDir);
        return ghostShell;
    }

    /** The Create contraption entity the diagram is anchored to, if any. */
    private static Entity contraptionAnchor() {
        if (!"CONTRAPTION".equals(anchorKind) || contraptionUuid == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (anchorEntity != null && anchorEntity.isRemoved()) anchorEntity = null;
        if (anchorEntity == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity.getUUID().equals(contraptionUuid)) {
                    anchorEntity = entity;
                    break;
                }
            }
        }
        return anchorEntity;
    }

    /** The live VS ship, resolved at most once per client tick. */
    private static Object vsShip() {
        if (!"VS".equals(anchorKind)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        // Once resolved, the ship object stays valid for the view's whole
        // lifetime; re-validating every tick against the snapshot world point
        // would drop a sailing ship after a few seconds (the snapshot point
        // goes stale and the diagram would snap to the static fallback).
        // Retry the lookup only while unresolved.
        if (vsShip != null) return vsShip;
        vsShip = VSClientCompat.shipForReanchor(mc.level, vsWorldRef, vsLocalRef);
        if (vsShip != null) {
            CBCTerminalBallistics.LOGGER.info("[CTB-HITCAM] ship reanchor resolved for shell {}",
                view != null ? view.projectileId() : -1);
        }
        return vsShip;
    }

    private static void recomputeBounds() {
        if (view == null) return;
        Vec3 sum = Vec3.ZERO;
        int count = 0;
        double maxDistSq = 0.0D;
        for (ClientboundTerminalBallisticsPacket.Plate plate : view.plates()) {
            sum = sum.add(plate.pos());
            count++;
        }
        if (count == 0 && view.spall() != null) {
            sum = view.spall().origin();
            count = 1;
        }
        anchorCenter = count > 0 ? sum.scale(1.0D / count) : Vec3.ZERO;
        for (ClientboundTerminalBallisticsPacket.Plate plate : view.plates()) {
            maxDistSq = Math.max(maxDistSq, plate.pos().distanceToSqr(anchorCenter));
        }
        if (view.spall() != null) {
            double spallDist = Math.sqrt(view.spall().origin().distanceToSqr(anchorCenter)) + view.spall().range();
            maxDistSq = Math.max(maxDistSq, spallDist * spallDist);
        }
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : view.entities()) {
            maxDistSq = Math.max(maxDistSq, hit.pos().distanceToSqr(anchorCenter));
        }
        orbitRadius = Math.max(5.5D, Math.min(12.0D, Math.sqrt(maxDistSq) + 3.0D));
    }

    private ClientTerminalView() {}
}
