package com.cbc_terminal_ballistics.client.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.client.VSClientCompat;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.hitcam.HitcamSample;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
 *
 * <p>The view is a <b>stage</b> holding one {@link Actor} per shell. The first
 * actor is the <em>primary</em>: it owns the camera (orbit center, azimuth,
 * close-up) and the anchor, and when a view opens from rest it plays a full
 * cinematic fly-in of its recorded path. Later shells from the SAME vehicle
 * merge in as secondary actors that have ALREADY hit by the time their packet
 * arrives, so they pop their impact marks (plates, spall, crew) onto the
 * running diagram the tick they land — no queued fly-in, their own fade timer,
 * so a sustained autocannon burst reads as an overlapping mess of fresh
 * impacts. A shell that hits while nothing is playing starts a fresh stage.
 * Each actor carries its own timeline arrays, offset to the stage clock so its
 * content plays when its own packet arrives.
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
    /** Minimum fade-out length once the stage really ends (also each shell's own fade). */
    private static final double FADE_TICKS = 20.0D;
    /** Hard ceiling on one stage's life in ticks: no arrival pattern may pin the view open. */
    private static final long MAX_STAGE_TICKS = 1200L;
    /**
     * Wall-clock ceiling on one stage's life, in milliseconds. The tick budget
     * above ages in server ticks, so a TPS collapse (heavy ship battles) or a
     * single-player pause — both freeze {@code getGameTime()} — can stretch a
     * 6-second view into wall-clock minutes. This cap runs on the client millisecond
     * clock, which advances through lag and pause, and always wins.
     */
    private static final long MAX_STAGE_WALL_MS = 90_000L;
    /**
     * Wall-clock quiet margin (ms) added on top of the configured quiet hold
     * for the {@code lastPacketMillis} backstop: covers one animation's length
     * plus the fade. The quiet hold itself is scaled from the config below so
     * long-hold setups keep their long hold.
     */
    private static final long QUIET_WALL_MARGIN_MS = 3_000L;

    /**
     * One shell's contribution to the stage. {@code primary} is the first
     * actor and owns the stage anchor and camera. {@code packet} is always the
     * LATEST packet for this shell (a shell that passes through several plates
     * dispatches cumulative packets under the same projectile id; each replaces
     * the actor in place so the diagram grows, it never re-fly-ins).
     *
     * <p>Package-visible so {@link ClientTerminalOverlay} can draw each actor
     * with its own packet content and timeline.
     */
    static final class Actor {
        ClientboundTerminalBallisticsPacket packet;
        final boolean primary;
        /**
         * Post-impact actor: the shell had already landed when it merged onto
         * the stage, so its content pops in place at {@link #startTick} — no
         * replayed flight path, no ghost tracer. False for the primary (a view
         * opening from rest replays the full cinematic fly-in).
         */
        boolean immediate;
        /** Stage tick the actor's animation (shell flight, flashes, growth) starts. */
        double startTick;
        /** Stage tick at which this actor's animation has fully played. */
        double animEndTick;

        // Animation timeline, precomputed once in the anchor's local space.
        // All tick fields are actor-local (0 = this actor's startTick).
        boolean hasPath;
        List<HitcamSample> trimmedPath = List.of();
        double[] pathCumLen;
        double totalLen;
        double flightTicks;
        double impactTime;
        double[] timeLut;
        Vec3 incomingDirLocal = Vec3.ZERO;
        double[] plateAppear;
        double[] spallAppear;
        double[][] blockDistFrac;
        double[] entityAppear;

        Actor(ClientboundTerminalBallisticsPacket packet, boolean primary) {
            this.packet = packet;
            this.primary = primary;
            this.startTick = 0.0D;
            this.animEndTick = 20.0D;
        }
    }

    /** All actors in arrival order; index 0 is the primary. */
    private static final List<Actor> actors = new ArrayList<>();
    private static long startTick;
    /** Client wall clock (ms) when the stage started; backs the hard expiry in {@link #expired()}. */
    private static long stageStartMillis;
    /**
     * Client wall clock (ms) of the most recent packet intake (start, merge,
     * same-shell refinement, restart). The quiet backstop in {@link #expired()}
     * measures silence from here, so a lag-frozen or paused view still closes
     * a few real seconds after the LAST shell's data arrived.
     */
    private static long lastPacketMillis;
    /** Client level the stage started in; a dimension change expires the stage (each level has its own game-time clock). */
    private static ResourceKey<Level> stageDim;
    /**
     * Stage tick (game time) at which the whole view ends: the latest content's
     * animation plus its quiet window, never before the base duration.
     */
    private static long endTick;
    /** Newest packet parked while the current animation plays; plays when it ends. */
    private static ClientboundTerminalBallisticsPacket pending;

    // Anchor (owned by the primary actor): null kind = world space,
    // CONTRAPTION = Create entity by UUID, VS = Valkyrien ship identified by
    // localRef (shipyard) + worldRef (world).
    private static String anchorKind;
    private static UUID contraptionUuid;
    private static Entity anchorEntity;
    private static Vec3 vsLocalRef = Vec3.ZERO;
    private static Vec3 vsWorldRef = Vec3.ZERO;
    private static Vec3 vsOffset = Vec3.ZERO;
    private static Object vsShip;

    private static Vec3 anchorCenter = Vec3.ZERO;
    private static double orbitRadius = 8.0D;

    // Per-frame animation snapshot consumed by the overlay and PiP renderer.
    public static final class Timeline {
        /** Stage age in ticks (with partial tick), same scale as every appear tick. */
        public final float age;
        public final float fade;
        /** True while the stage may still accept more shells (burst window open). */
        public final boolean alive;
        public final boolean hasPath;
        public final double flightTicks;
        public final double shellProgress;
        @Nullable public final Vec3 shellPos;
        public final Vec3 shellDir;
        /** 0 once the shell has passed the end of its path (plus a short linger). */
        public final float shellAlpha;
        public final double[] plateAppear;
        /** Per-cone appear tick (stage scale); index 0 is the main armour cone. */
        public final double[] spallStarts;
        public final float[] spallGrowths;
        public final double[][] blockDistFracs;
        public final double[] entityAppear;

        Timeline(float age, float fade, boolean alive, boolean hasPath, double flightTicks, double shellProgress,
                 @Nullable Vec3 shellPos, Vec3 shellDir, float shellAlpha,
                 double[] plateAppear, double[] spallStarts, float[] spallGrowths,
                 double[][] blockDistFracs, double[] entityAppear) {
            this.age = age;
            this.fade = fade;
            this.alive = alive;
            this.hasPath = hasPath;
            this.flightTicks = flightTicks;
            this.shellProgress = shellProgress;
            this.shellPos = shellPos;
            this.shellDir = shellDir;
            this.shellAlpha = shellAlpha;
            this.plateAppear = plateAppear;
            this.spallStarts = spallStarts;
            this.spallGrowths = spallGrowths;
            this.blockDistFracs = blockDistFracs;
            this.entityAppear = entityAppear;
        }

        public int spallCount() {
            return spallStarts == null ? 0 : spallStarts.length;
        }

        public double spallStart(int cone) {
            return spallStarts != null && cone < spallStarts.length ? spallStarts[cone] : Double.MAX_VALUE;
        }

        public float spallGrowth(int cone) {
            return spallGrowths != null && cone < spallGrowths.length ? spallGrowths[cone] : 0.0F;
        }

        public double blockDistFrac(int cone, int block) {
            if (blockDistFracs == null || cone >= blockDistFracs.length) return 0.0D;
            double[] fracs = blockDistFracs[cone];
            return fracs != null && block < fracs.length ? fracs[block] : 0.0D;
        }

        /** 0..1 brightness boost right after a plate is struck. */
        public float plateFlash(int index) {
            if (plateAppear == null || index >= plateAppear.length) return 0.0F;
            float since = age - (float) plateAppear[index];
            if (since < 0.0F) return 0.0F;
            return Mth.clamp(1.0F - since / (float) PLATE_FLASH_TICKS, 0.0F, 1.0F);
        }
    }

    // ---- packet intake ----------------------------------------------------------

    /**
     * Accept a terminal packet. A packet whose projectile id matches an actor
     * already on stage refines that actor in place (a shell that hit several
     * plates in a row dispatches cumulative snapshots under one id — the
     * diagram grows, the shell never re-pops). A NEW shell whose anchor is
     * compatible with the current primary merges as a secondary actor for as
     * long as the stage is alive (burst accumulation, config
     * {@code hitcamTerminalMergeBurst}). Merging is CONCURRENT, never queued:
     * the shell has already hit, so its marks start the tick it arrives, with
     * its own fade timer — at high RPM the diagram becomes an overlapping mess
     * of fresh plates/spalls instead of shells waiting behind an earlier
     * animation. Anything else (different vehicle, burst off) waits until the
     * current animation finishes, then restarts the view on it.
     */
    public static void accept(ClientboundTerminalBallisticsPacket packet) {
        lastPacketMillis = Util.getMillis();
        if (!actors.isEmpty()) {
            // Same-shell refinement: replace the actor's packet in place.
            for (int i = 0; i < actors.size(); i++) {
                if (actors.get(i).packet.projectileId() == packet.projectileId()) {
                    refreshActor(i, packet);
                    return;
                }
            }
            // Drop secondary actors whose per-shell diagram has fully faded, so
            // a long burst stream keeps merging instead of filling the actor cap.
            pruneFadedActors();
            if (ageTicks() < endTick) {
                boolean burst = burstEnabled();
                boolean mergeable = burst && burstActors() > 1
                    && anchorsCompatible(actors.get(0).packet.anchor(), packet.anchor());
                if (mergeable) {
                    // Concurrent burst: pop this shell's marks onto the running
                    // diagram right now, overlapping whatever is still showing.
                    addActor(packet);
                    return;
                }
                if (ageTicks() < stageBusyEndTick()) {
                    // A different-vehicle shell (or burst off) mid-animation must
                    // wait until every actor's content has played — cutting an
                    // animation already on screen reads as a glitch.
                    park(packet, "animation still playing");
                    return;
                }
                // Stage at rest: a new engagement restarts the view on it.
                start(packet, "previous animation finished");
                return;
            }
        }
        start(packet, "no active view");
    }

    /**
     * Removes secondary actors whose per-shell diagram has fully faded, so a
     * long burst stream keeps merging into the running stage instead of
     * filling the actor cap (which would otherwise force dropping shells that
     * still have content to show). The primary (owns the anchor/camera) and
     * the newest actor (holds the view open for the close) are always kept.
     */
    private static void pruneFadedActors() {
        if (actors.size() <= 1) return;
        long age = ageTicks();
        for (int i = actors.size() - 1; i >= 1; i--) {
            if (i == actors.size() - 1) continue; // newest stays for the close
            Actor actor = actors.get(i);
            if (age >= actorFadeStartTick(actor) + FADE_TICKS) {
                CBCTerminalBallistics.LOGGER.debug(
                    "[CTB-HITCAM] burst actor {} pruned (diagram faded, shell={})",
                    i, actor.packet.projectileId());
                actors.remove(i);
            }
        }
        recomputeEndTick();
    }

    /** Stage tick at which the LAST actor's animation ends; before then the stage is busy. */
    private static double stageBusyEndTick() {
        double end = 0.0D;
        for (Actor a : actors) end = Math.max(end, a.animEndTick);
        return end;
    }

    private static void park(ClientboundTerminalBallisticsPacket packet, String why) {
        pending = packet;
        CBCTerminalBallistics.LOGGER.debug(
            "[CTB-HITCAM] parked terminal packet shell={} summary=\"{}\" plates={} (why: {} age={} endTick={} animEnd={})",
            packet.projectileId(), packet.summary(), packet.plates().size(),
            why, ageTicks(), endTick - startTick, primaryAnimEndTick());
    }

    /** Same-shell cumulative snapshot: swap in the fuller packet, rebuild its timeline. */
    private static void refreshActor(int index, ClientboundTerminalBallisticsPacket packet) {
        Actor actor = actors.get(index);
        CBCTerminalBallistics.LOGGER.debug(
            "[CTB-HITCAM] same-shell refinement shell={} plates={}->{} spalls={}->{} entities={}->{} criticals={}->{}",
            packet.projectileId(),
            actor.packet.plates().size(), packet.plates().size(),
            actor.packet.spalls().size(), packet.spalls().size(),
            actor.packet.entities().size(), packet.entities().size(),
            actor.packet.criticals().size(), packet.criticals().size());
        // The actor keeps its start tick: the content updates in place and the
        // timeline arrays are rebuilt against the same stage clock, so a shell
        // that stopped on plate 3 of 4 simply gains plate 4 mid-view.
        actor.packet = packet;
        buildTimeline(actor);
        // A refinement can grow the diagram (a late-arriving HE blast, plate 4
        // of 4); re-frame the camera around the new content, not just the view
        // that was there when the stage opened.
        recomputeBounds();
        if (burstEnabled()) {
            recomputeEndTick();
        }
    }

    /**
     * A new shell merged onto the running stage. The shell has ALREADY hit —
     * packets are dispatched at the first armour plate strike — so the actor is
     * post-impact ({@code immediate}): it starts this tick and its plates,
     * spall and crew marks pop onto the diagram as the beats of a short static
     * timeline play, then fade on the actor's own timer. Nothing is queued
     * behind earlier content and nothing replays a flight, so a sustained
     * autocannon burst renders as an overlapping mess of fresh impacts.
     */
    private static void addActor(ClientboundTerminalBallisticsPacket packet) {
        Actor actor = new Actor(packet, false);
        actor.immediate = true;
        actor.startTick = ageTicks();
        actors.add(actor);
        buildTimeline(actor);
        // Rolling window at the actor cap: drop whichever finished secondary is
        // only holding on screen (else the oldest secondary), so the newest hit
        // always renders and the view never restarts mid-burst (which would yank
        // the camera). pruneFadedActors may already have freed a slot.
        while (actors.size() > burstActors()) {
            int drop = indexOfExpendableSecondary();
            if (drop < 1) break; // no secondary worth dropping; keep the newest
            CBCTerminalBallistics.LOGGER.debug(
                "[CTB-HITCAM] burst actor {} dropped for shell={} (cap {})",
                drop, actors.get(drop).packet.projectileId(), burstActors());
            actors.remove(drop);
        }
        CBCTerminalBallistics.LOGGER.debug(
            "[CTB-HITCAM] burst merged shell={} onto view shell={} (actor {} of {})",
            packet.projectileId(), actors.get(0).packet.projectileId(),
            actors.size(), burstActors());
        recomputeEndTick();
        ClientHitcam.startTerminalView();
    }

    /**
     * Picks the secondary actor with the least to lose when the actor cap is
     * exceeded: a secondary whose per-shell diagram is already fully faded (its
     * content is only holding), else the oldest secondary — never the primary
     * (owns the anchor/camera) and never the newest actor (this shell closes
     * the view). Returns -1 when no secondary is droppable.
     */
    private static int indexOfExpendableSecondary() {
        long age = ageTicks();
        for (int i = 1; i < actors.size() - 1; i++) {
            if (age >= actorFadeStartTick(actors.get(i)) + FADE_TICKS) return i;
        }
        return actors.size() <= 2 ? -1 : 1;
    }

    /**
     * Stage tick at which this actor's per-shell diagram fade begins: once its
     * whole animation has played out plus the burst quiet hold measured from
     * its impact (whichever is later), so a fast shell's marks hold for the
     * quiet window even when a follow-up has already taken over the stage. A
     * post-impact actor has ALREADY hit when it starts (its "impact" is its
     * start tick), so its quiet hold runs straight from arrival.
     */
    private static double actorFadeStartTick(Actor actor) {
        double impact = actor.immediate ? 0.0D : actor.impactTime;
        return Math.max(actor.animEndTick, actor.startTick + impact + burstQuietTicks());
    }

    /**
     * Two packets can share one stage when they live in the same coordinate
     * frame. World anchors are always compatible with each other. Vehicle
     * anchors (same contraption UUID, or a VS anchor whose ship-local reference
     * agrees) are compatible only within their own kind.
     */
    private static boolean anchorsCompatible(@Nullable ClientboundTerminalBallisticsPacket.Anchor a,
                                             @Nullable ClientboundTerminalBallisticsPacket.Anchor b) {
        if (a == null || b == null) return a == null && b == null;
        if (!a.kind().equals(b.kind())) return false;
        if ("CONTRAPTION".equals(a.kind())) {
            return a.uuid() != null && a.uuid().equals(b.uuid());
        }
        if ("VS".equals(a.kind())) {
            if (a.localRef() == null || b.localRef() == null) return false;
            // The world ref drifts as the ship moves between the two impacts;
            // compare in the ship's own frame, which is what the packet content
            // is expressed in. A large local-frame divergence means a different ship.
            return a.localRef().distanceToSqr(b.localRef()) < 64.0D * 64.0D;
        }
        return false;
    }

    private static void start(ClientboundTerminalBallisticsPacket packet, String reason) {
        pending = null;
        actors.clear();
        Actor primary = new Actor(packet, true);
        primary.startTick = 0.0D;
        actors.add(primary);
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
        stageStartMillis = Util.getMillis();
        stageDim = mc.level != null ? mc.level.dimension() : null;
        recomputeBounds();
        buildTimeline(primary);
        recomputeEndTick();
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] terminal view {} shell={} pathPts={} trimmed={} flight={} impact={} animEnd={} endTick={} anchor={} ship={} center={} cam0={} look0={}",
            reason, packet.projectileId(), packet.path().size(), primary.trimmedPath.size(),
            String.format("%.1f", primary.flightTicks), String.format("%.1f", primary.impactTime),
            String.format("%.1f", primary.animEndTick), endTick - startTick, anchorKind, vsShip != null,
            fmtVec(center(0)), fmtVec(cameraPos(0)), fmtVec(lookPoint(0)));
        ClientHitcam.startTerminalView();
    }

    /** Recomputed the absolute stage end from every actor's content plus its quiet hold. */
    private static void recomputeEndTick() {
        // The stage ends once every actor's content has played plus its quiet
        // hold (burst on), or after the primary's animation plus a fixed tail
        // (burst off — the old single-shell duration). Never below the base
        // duration, and hard-capped so no arrival pattern can pin the view
        // open for tens of seconds after the fire stops. All actor ticks are
        // relative to the stage start; endTick itself is absolute game time.
        double rel = baseDuration();
        if (!burstEnabled()) {
            Actor primary = primary();
            double animEnd = primary == null ? 0.0D : primary.animEndTick;
            rel = Math.max(rel, animEnd + 20.0D);
        } else {
            for (Actor actor : actors) {
                rel = Math.max(rel, actor.animEndTick + burstQuietTicks());
            }
            // A still-running stream must never let the stage expire under
            // itself: every recompute (pruning, cap drops) keeps a floor of the
            // current age plus the fade window, so an arriving shell always
            // finds the stage alive and merges instead of restarting it.
            rel = Math.max(rel, ageTicks() + FADE_TICKS);
        }
        long maxRel = Math.max((long) baseDuration() * 3L, MAX_STAGE_TICKS);
        endTick = startTick + Math.min((long) Math.ceil(rel), maxRel);
    }

    public static void clear() {
        actors.clear();
        anchorEntity = null;
        anchorKind = null;
        contraptionUuid = null;
        vsShip = null;
        endTick = 0L;
        stageDim = null;
        ghostShell = null;
        // {@link #pending} deliberately survives: it is flushed by
        // {@link #clientTick()} the moment the current view is gone.
    }

    /** True while any terminal animation is actively playing (merged fly-ins count). */
    public static boolean animationInProgress() {
        return !actors.isEmpty() && ageTicks() < stageBusyEndTick();
    }

    /** Plays a parked packet once the current animation has finished. Call once per client tick. */
    public static void clientTick() {
        if (pending == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            pending = null;
            return;
        }
        if (actors.isEmpty() || ageTicks() >= stageBusyEndTick() || ageTicks() >= endTick) {
            ClientboundTerminalBallisticsPacket packet = pending;
            pending = null;
            CBCTerminalBallistics.LOGGER.debug("[CTB-HITCAM] playing parked terminal packet shell={} plates={}",
                packet.projectileId(), packet.plates().size());
            accept(packet);
        }
    }

    public static boolean active() {
        return !actors.isEmpty();
    }

    public static boolean expired() {
        if (actors.isEmpty()) return true;
        Minecraft mc = Minecraft.getInstance();
        // A level swap/dimension change resets the game-time clock; a stage
        // started on the old clock would otherwise age backwards and never end.
        if (mc.level == null || stageDim == null || mc.level.dimension() != stageDim) return true;
        if (mc.level.getGameTime() < startTick) return true;
        // Wall-clock backstops. Game ticks freeze under TPS collapse and
        // pause, which would otherwise hold the view (and the burst-hold
        // artillery guard) open for minutes of real time. The quiet window
        // measures silence since the LAST packet — the view must clear a few
        // real seconds after the final shell's data no matter how slow the
        // tick clock runs — and scales from the configured quiet hold so
        // long-hold setups keep theirs. The absolute stage cap bounds a stage
        // that somehow keeps its quiet window refreshed forever.
        long now = Util.getMillis();
        if (now - lastPacketMillis > quietWallMs()) return true;
        if (now - stageStartMillis > MAX_STAGE_WALL_MS) return true;
        return ageTicks() >= endTick;
    }

    /** Total display budget of the current stage (for status readers). */
    public static int duration() {
        return (int) Math.max(20L, endTick - startTick);
    }

    private static boolean burstEnabled() {
        return TBConfig.HITCAM_TERMINAL_BURST.get();
    }

    private static int baseDuration() {
        return Math.max(20, TBConfig.HITCAM_TERMINAL_DURATION.get());
    }

    private static int burstQuietTicks() {
        return Math.max(10, TBConfig.HITCAM_TERMINAL_BURST_QUIET_TICKS.get());
    }

    /**
     * Wall-clock silence that must follow the last packet before the view is
     * force-expired regardless of the (possibly frozen) tick clock: the
     * configured quiet hold converted to real time plus a margin for one
     * animation and the fade.
     */
    private static long quietWallMs() {
        return burstQuietTicks() * 50L + (long) (FADE_TICKS * 50.0D) + QUIET_WALL_MARGIN_MS;
    }

    private static int burstActors() {
        return Mth.clamp(TBConfig.HITCAM_TERMINAL_BURST_ACTORS.get(), 1, 64);
    }

    // ---- primary accessors --------------------------------------------------------

    /** The primary actor (index 0), or null when the stage is empty. */
    @Nullable
    static Actor primaryActor() {
        return actors.isEmpty() ? null : actors.get(0);
    }

    /** Live actor list for the overlay, in arrival order. */
    static List<Actor> actorsView() {
        return actors;
    }

    /** Stage tick at which the primary actor's own animation ends. */
    static double primaryAnimEndTick() {
        Actor primary = primaryActor();
        return primary == null ? 20.0D : primary.animEndTick;
    }

    private static Actor primary() {
        return actors.isEmpty() ? null : actors.get(0);
    }

    public static String summary() {
        Actor primary = primary();
        if (primary == null) return "";
        String s = primary.packet.summary();
        // A merged burst shell may have scored the critical even when the first
        // hit was a clean penetration — surface it in the status line too, not
        // just in the critical list.
        if (!s.contains("CRITICAL")) {
            for (Actor a : actors) {
                if (!a.packet.criticals().isEmpty()) {
                    s = "CRITICAL HIT \u00b7 " + s;
                    break;
                }
            }
        }
        return s;
    }

    /** How many shells have stacked onto the stage (1 = single engagement). */
    public static int actorCount() {
        return actors.size();
    }

    public static String entityTypeKey() {
        Actor primary = primary();
        return primary == null ? "" : primary.packet.entityTypeKey();
    }

    public static float age(float partialTick) {
        return ageTicks() + partialTick;
    }

    private static long ageTicks() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0L : mc.level.getGameTime() - startTick;
    }

    public static List<HitcamSample> path() {
        Actor primary = primary();
        return primary == null ? List.of() : primary.packet.path();
    }

    public static List<ClientboundTerminalBallisticsPacket.Plate> plates() {
        Actor primary = primary();
        return primary == null ? List.of() : primary.packet.plates();
    }

    /** All spall cones in order; index 0 is the main armour cone. */
    public static List<ClientboundTerminalBallisticsPacket.Spall> spalls() {
        Actor primary = primary();
        return primary == null ? List.of() : primary.packet.spalls();
    }

    /** The primary (main armour) spall cone, or null when nothing spalled. */
    @Nullable
    public static ClientboundTerminalBallisticsPacket.Spall spall() {
        List<ClientboundTerminalBallisticsPacket.Spall> list = spalls();
        return list.isEmpty() ? null : list.get(0);
    }

    public static List<ClientboundTerminalBallisticsPacket.EntityHit> entities() {
        Actor primary = primary();
        return primary == null ? List.of() : primary.packet.entities();
    }

    /** Critical-hit evaluation entries (destroyed components, crew kills), aggregated across all actors. */
    public static List<ClientboundTerminalBallisticsPacket.CriticalEntry> criticals() {
        if (actors.isEmpty()) return List.of();
        if (actors.size() == 1) return actors.get(0).packet.criticals();
        List<ClientboundTerminalBallisticsPacket.CriticalEntry> all = new ArrayList<>();
        for (Actor actor : actors) {
            all.addAll(actor.packet.criticals());
        }
        return all;
    }

    // ---- geometry (shared stage anchor; primary owns it) -------------------------

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

    /** Compact "x,y z" rendering of a vector for one-line hitcam diagnostics. */
    private static String fmtVec(Vec3 v) {
        return v == null ? "null" : String.format("%.0f,%.0f,%.0f", v.x, v.y, v.z);
    }

    /**
     * The point the camera watches: the animated replay shell while it flies
     * in, but only up to the impact point — a pass-through shell continuing out
     * the far side does not drag the view with it. From impact on, the look
     * settles onto the terminal focus (spall origin if the plate spalled, else
     * the final plate position) to follow the spall progression. Always the
     * FIRST engagement: secondary actors render into the diagram but never pull
     * the camera.
     */
    public static Vec3 lookPoint(float partialTick) {
        Actor primary = primary();
        if (primary == null) return Vec3.ZERO;
        Vec3 focus = terminalFocus(partialTick);
        if (!primary.hasPath || primary.flightTicks <= 0.0D) return focus;
        float age = age(partialTick);
        double impactFraction = Mth.clamp(primary.impactTime / primary.flightTicks, 0.0D, 1.0D);
        double progress = Math.min(progressAtTime(primary, age / (float) primary.flightTicks), impactFraction);
        Vec3 follow = toWorld(pathPointAt(primary, progress), partialTick);
        if (age >= primary.impactTime) {
            double blend = Mth.clamp((age - (float) primary.impactTime) / 10.0D, 0.0D, 1.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            return follow.lerp(focus, blend);
        }
        return follow;
    }

    /**
     * Where the camera settles after the shell lands. Always the FIRST
     * engagement: the main armour plate's spall site, or the first plate
     * itself. Secondary engagements (later plates, a second ship) render into
     * the diagram but never drag the camera away from the first hit.
     */
    private static Vec3 terminalFocus(float partialTick) {
        ClientboundTerminalBallisticsPacket.Spall spall = spall();
        if (spall != null) return toWorld(spall.origin(), partialTick);
        List<ClientboundTerminalBallisticsPacket.Plate> plateList = plates();
        if (!plateList.isEmpty()) {
            return toWorld(plateList.get(0).pos(), partialTick);
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
        Actor primary = primary();
        if (primary == null) return Vec3.ZERO;
        Vec3 c = center(partialTick);
        double age = age(partialTick);
        double sweepEnd = Math.max(1.0D, baseDuration() * SWEEP_END_FRACTION);
        double t = Mth.clamp(age / sweepEnd, 0.0D, 1.0D);
        double eased = t * t * (3.0D - 2.0D * t);
        double base = incomingAzimuth(primary, partialTick);
        double angle = base + SWEEP_START_OFFSET + eased * (SWEEP_RADIANS - SWEEP_START_OFFSET);
        // War-Thunder close-up: from impact on, the orbit eases in toward the
        // penetration point while the x-ray and spall play out. An explosive
        // shell keeps the wide framing instead — the whole point of its view
        // is the detonation, which the close-in zoom would push out of frame.
        double r = orbitRadius;
        ClientboundTerminalBallisticsPacket.Blast blast = primary.packet.blast();
        boolean keepWide = blast != null && blast.explosive();
        if (!keepWide && primary.impactTime > 0.0D && age >= primary.impactTime) {
            double blend = Mth.clamp((age - primary.impactTime) / CLOSEUP_TICKS, 0.0D, 1.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            r *= 1.0D - CLOSEUP_FRACTION * blend;
        }
        return new Vec3(c.x + Math.cos(angle) * r, c.y + r * 0.5D, c.z + Math.sin(angle) * r);
    }

    /** World azimuth on the approach side of the recorded incoming direction, so the sweep starts face-on to the impact face. */
    private static double incomingAzimuth(Actor actor, float partialTick) {
        if (actor.incomingDirLocal.lengthSqr() < 1.0e-8D) return Math.PI * 0.25D;
        Vec3 dir = toWorldDir(actor.incomingDirLocal, partialTick);
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horiz < 1.0e-6D) return Math.PI * 0.25D;
        // Negated: the camera must sit behind the incoming shell (approach
        // side) to look at the impact face. Using the travel direction put it
        // on the far side, starting at the exit face and sweeping TO the face.
        return Math.atan2(dir.z, dir.x) + Math.PI;
    }

    // ---- animation timeline ------------------------------------------------------

    private static void buildTimeline(Actor actor) {
        List<HitcamSample> recorded = actor.packet.path();
        if (actor.immediate) {
            // Post-impact actor: the shell had already landed when its packet
            // arrived, so there is no flight to replay. The appear delays are
            // computed below as if the path did not exist — every plate it
            // struck pops at once (all plates hit at impact), spall cones grow
            // in sequence from arrival, and the marks fade on the actor's own
            // timer, overlapping earlier shells (the concurrent-burst design).
            actor.trimmedPath = List.of();
            actor.hasPath = false;
        } else {
            actor.trimmedPath = trimPath(recorded);
            actor.hasPath = actor.trimmedPath.size() >= 2;
        }
        actor.flightTicks = 0.0D;
        actor.totalLen = 0.0D;
        actor.pathCumLen = null;
        actor.impactTime = 0.0D;
        actor.timeLut = null;
        actor.incomingDirLocal = Vec3.ZERO;
        if (actor.hasPath) {
            actor.pathCumLen = new double[actor.trimmedPath.size()];
            double acc = 0.0D;
            for (int i = 1; i < actor.trimmedPath.size(); i++) {
                acc += samplePoint(actor.trimmedPath.get(i - 1)).distanceTo(samplePoint(actor.trimmedPath.get(i)));
                actor.pathCumLen[i] = acc;
            }
            actor.totalLen = acc;
            actor.flightTicks = Mth.clamp(actor.totalLen * FLIGHT_TICKS_PER_BLOCK, FLIGHT_MIN_TICKS, FLIGHT_MAX_TICKS);
            Vec3 dirIn = samplePoint(actor.trimmedPath.get(actor.trimmedPath.size() - 1)).subtract(samplePoint(actor.trimmedPath.get(0)));
            if (dirIn.lengthSqr() > 1.0e-8D) actor.incomingDirLocal = dirIn.normalize();
            buildTimeLut(actor);
        }

        List<ClientboundTerminalBallisticsPacket.Plate> plateList = actor.packet.plates();
        actor.plateAppear = new double[plateList.size()];
        for (int i = 0; i < plateList.size(); i++) {
            actor.plateAppear[i] = actor.hasPath
                ? actor.flightTicks * timeAtProgress(actor, fractionOf(actor, plateList.get(i).pos())) : 0.0D;
        }

        List<ClientboundTerminalBallisticsPacket.Spall> spallList = actor.packet.spalls();
        actor.spallAppear = new double[spallList.size()];
        actor.blockDistFrac = new double[spallList.size()][];
        double lastPlate = actor.plateAppear.length > 0 ? actor.plateAppear[actor.plateAppear.length - 1] : 0.0D;
        for (int c = 0; c < spallList.size(); c++) {
            ClientboundTerminalBallisticsPacket.Spall cone = spallList.get(c);
            double start = actor.hasPath
                ? actor.flightTicks * timeAtProgress(actor, fractionOf(actor, cone.origin())) : 0.0D;
            // The spall waits for the struck plate's flash to finish, so the
            // beats read in order: shell travels, block highlights, spall
            // travels. Secondary cones (later plates, a second ship) queue
            // after the previous cone's growth so each reads on its own.
            double queue = c > 0 ? actor.spallAppear[c - 1] + SPALL_GROW_TICKS + 2.0D : 0.0D;
            actor.spallAppear[c] = Math.max(Math.max(start, lastPlate + PLATE_FLASH_TICKS + 2.0D), queue);
            actor.blockDistFrac[c] = new double[cone.blocks().size()];
            double range = Math.max(cone.range(), 0.5D);
            for (int i = 0; i < cone.blocks().size(); i++) {
                actor.blockDistFrac[c][i] = Mth.clamp(cone.blocks().get(i).pos().distanceTo(cone.origin()) / range, 0.0D, 1.0D);
            }
        }
        ClientboundTerminalBallisticsPacket.Spall firstCone = spallList.isEmpty() ? null : spallList.get(0);
        // The impact moment (camera close-up start, look easing) is when the
        // shell lands on the last plate — not the delayed spall start.
        actor.impactTime = actor.plateAppear.length > 0 ? actor.plateAppear[actor.plateAppear.length - 1]
            : (firstCone != null ? actor.spallAppear[0] : (actor.hasPath ? actor.flightTicks : 0.0D));

        List<ClientboundTerminalBallisticsPacket.EntityHit> entityList = actor.packet.entities();
        actor.entityAppear = new double[entityList.size()];
        for (int i = 0; i < entityList.size(); i++) {
            ClientboundTerminalBallisticsPacket.EntityHit hit = entityList.get(i);
            double direct = actor.hasPath
                ? actor.flightTicks * timeAtProgress(actor, fractionOf(actor, hit.pos())) : 0.0D;
            double viaSpall = 0.0D;
            if (firstCone != null) {
                double range = Math.max(firstCone.range(), 0.5D);
                viaSpall = actor.spallAppear[0] + SPALL_GROW_TICKS
                    * Mth.clamp(hit.pos().distanceTo(firstCone.origin()) / range, 0.0D, 1.0D);
            }
            actor.entityAppear[i] = Math.max(direct, viaSpall);
        }

        // Tick at which the animation has fully played: last plate flashed,
        // every spall cone fully grown, entity pops done. Actor-local.
        double end = 0.0D;
        for (double appear : actor.spallAppear) end = Math.max(end, appear + SPALL_GROW_TICKS);
        for (double a : actor.entityAppear) end = Math.max(end, a + 8.0D);
        if (actor.plateAppear.length > 0) {
            end = Math.max(end, actor.plateAppear[actor.plateAppear.length - 1] + PLATE_FLASH_TICKS);
        }
        if (!actor.hasPath) end = Math.max(end, 30.0D); // static diagram still deserves a beat
        actor.animEndTick = actor.startTick + Math.max(end + 4.0D, 10.0D);
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
    private static void buildTimeLut(Actor actor) {
        double f = Mth.clamp(TBConfig.HITCAM_SLOWMO_END_FRACTION.get(), 0.0D, 1.0D);
        double m = Mth.clamp(TBConfig.HITCAM_SLOWMO_MIN_SPEED.get(), 0.01D, 1.0D);
        actor.timeLut = new double[LUT_STEPS + 1];
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
            actor.timeLut[i] = raw / norm;
        }
        actor.timeLut[LUT_STEPS] = 1.0D;
    }

    /** Path progress 0..1 the shell has reached at normalized flight time {@code u} 0..1. */
    private static double progressAtTime(Actor actor, double u) {
        if (actor.timeLut == null) return Mth.clamp(u, 0.0D, 1.0D);
        u = Mth.clamp(u, 0.0D, 1.0D);
        double x = u * LUT_STEPS;
        int i = Math.min((int) x, LUT_STEPS - 1);
        return Mth.lerp(x - i, actor.timeLut[i], actor.timeLut[i + 1]);
    }

    /** Normalized flight time 0..1 at which the shell reaches path progress {@code p} 0..1. */
    private static double timeAtProgress(Actor actor, double p) {
        if (actor.timeLut == null) return Mth.clamp(p, 0.0D, 1.0D);
        p = Mth.clamp(p, 0.0D, 1.0D);
        for (int i = 1; i <= LUT_STEPS; i++) {
            if (actor.timeLut[i] >= p) {
                double p0 = actor.timeLut[i - 1];
                double p1 = actor.timeLut[i];
                double frac = p1 > p0 ? (p - p0) / (p1 - p0) : 0.0D;
                return ((i - 1) + frac) / LUT_STEPS;
            }
        }
        return 1.0D;
    }

    /** Recorded path fraction (0..1) of a local point: nearest path sample. */
    private static double fractionOf(Actor actor, Vec3 local) {
        if (!actor.hasPath || actor.totalLen < 1.0e-6D) return 0.0D;
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < actor.trimmedPath.size(); i++) {
            double d = samplePoint(actor.trimmedPath.get(i)).distanceToSqr(local);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return actor.pathCumLen[best] / actor.totalLen;
    }

    /** Local path point at progress 0..1, linearly interpolated along the polyline (primary). */
    public static Vec3 pathPointAt(double progress) {
        Actor primary = primary();
        return primary == null ? Vec3.ZERO : pathPointAt(primary, progress);
    }

    private static Vec3 pathPointAt(Actor actor, double progress) {
        if (actor.trimmedPath.isEmpty()) return Vec3.ZERO;
        if (actor.trimmedPath.size() == 1 || actor.totalLen < 1.0e-6D) return samplePoint(actor.trimmedPath.get(0));
        double target = Mth.clamp(progress, 0.0D, 1.0D) * actor.totalLen;
        for (int i = 1; i < actor.trimmedPath.size(); i++) {
            if (actor.pathCumLen[i] >= target) {
                double segLen = actor.pathCumLen[i] - actor.pathCumLen[i - 1];
                double frac = segLen < 1.0e-6D ? 0.0D : (target - actor.pathCumLen[i - 1]) / segLen;
                return samplePoint(actor.trimmedPath.get(i - 1)).lerp(samplePoint(actor.trimmedPath.get(i)), frac);
            }
        }
        return samplePoint(actor.trimmedPath.get(actor.trimmedPath.size() - 1));
    }

    /** Local path direction at progress 0..1 (segment direction; primary). */
    public static Vec3 pathDirAt(double progress) {
        Actor primary = primary();
        return primary == null ? Vec3.ZERO : pathDirAt(primary, progress);
    }

    private static Vec3 pathDirAt(Actor actor, double progress) {
        if (actor.trimmedPath.size() < 2) return actor.incomingDirLocal;
        double target = Mth.clamp(progress, 0.0D, 1.0D) * actor.totalLen;
        for (int i = 1; i < actor.trimmedPath.size(); i++) {
            if (actor.pathCumLen[i] >= target) {
                Vec3 dir = samplePoint(actor.trimmedPath.get(i)).subtract(samplePoint(actor.trimmedPath.get(i - 1)));
                return dir.lengthSqr() < 1.0e-8D ? actor.incomingDirLocal : dir.normalize();
            }
        }
        return actor.incomingDirLocal;
    }

    /** Cumulative path fraction of sample {@code index} (for progressive drawing; primary). */
    public static double pathFraction(int index) {
        Actor primary = primary();
        if (primary == null || !primary.hasPath || primary.totalLen < 1.0e-6D) return 0.0D;
        int i = Mth.clamp(index, 0, primary.pathCumLen.length - 1);
        return primary.pathCumLen[i] / primary.totalLen;
    }

    public static int pathPointCount() {
        Actor primary = primary();
        return primary == null ? 0 : primary.trimmedPath.size();
    }

    /** Local position of path sample {@code index} (primary). */
    public static Vec3 pathPoint(int index) {
        Actor primary = primary();
        return primary == null || primary.trimmedPath.isEmpty() ? Vec3.ZERO
            : samplePoint(primary.trimmedPath.get(Mth.clamp(index, 0, primary.trimmedPath.size() - 1)));
    }

    // Per-actor path accessors for the overlay's per-actor progressive drawing.

    static int actorPathCount(Actor actor) {
        return actor.trimmedPath.size();
    }

    /** Local position of one actor's path sample. */
    static Vec3 actorPathPoint(Actor actor, int index) {
        return actor.trimmedPath.isEmpty() ? Vec3.ZERO
            : samplePoint(actor.trimmedPath.get(Mth.clamp(index, 0, actor.trimmedPath.size() - 1)));
    }

    /** Cumulative path fraction of one actor's sample {@code index}. */
    static double actorPathFraction(Actor actor, int index) {
        if (!actor.hasPath || actor.totalLen < 1.0e-6D || actor.pathCumLen == null) return 0.0D;
        int i = Mth.clamp(index, 0, actor.pathCumLen.length - 1);
        return actor.pathCumLen[i] / actor.totalLen;
    }

    private static Vec3 samplePoint(HitcamSample sample) {
        return new Vec3(sample.x(), sample.y(), sample.z());
    }

    // ---- per-frame state ----------------------------------------------------------

    /**
     * Per-frame animation state of the PRIMARY actor — the single-engagement
     * behaviour the overlay and PiP used before burst accumulation, unchanged.
     */
    public static Timeline snapshot(float partialTick) {
        Actor primary = primary();
        if (primary == null) {
            float age = age(partialTick);
            return new Timeline(age, 0.0F, false, false, 0.0D, 1.0D, null, Vec3.ZERO, 0.0F,
                new double[0], new double[0], new float[0], new double[0][], new double[0]);
        }
        return actorSnapshot(primary, partialTick);
    }

    /**
     * Per-frame animation snapshot of one actor, evaluated against the stage
     * clock. Appear ticks are shifted to the stage scale (actor-local + the
     * actor's start tick) so the overlay compares them against {@code age}.
     * Returns null when the actor's animation has not started yet (a later
     * burst shell still waiting its turn).
     */
    @Nullable
    static Timeline actorSnapshot(Actor actor, float partialTick) {
        float stageAge = age(partialTick);
        double localAge = stageAge - actor.startTick;
        double shift = actor.startTick;
        float[] growths = new float[actor.spallAppear.length];
        double[] spallStarts = new double[actor.spallAppear.length];
        for (int i = 0; i < growths.length; i++) {
            double appear = actor.spallAppear[i] + shift;
            spallStarts[i] = appear;
            growths[i] = (float) Mth.clamp((localAge - actor.spallAppear[i]) / SPALL_GROW_TICKS, 0.0D, 1.0D);
        }
        double[] plateAppear = shiftArray(actor.plateAppear, shift);
        double[] entityAppear = shiftArray(actor.entityAppear, shift);
        if (localAge < 0.0D) {
            // Later actor not yet on stage: nothing of it shows yet, but return a
            // valid (empty-progress) snapshot so callers can still read its data.
            return new Timeline(stageAge, snapshotFade(actor, stageAge), stageAlive(stageAge), actor.hasPath,
                actor.flightTicks, 0.0D, null, Vec3.ZERO, 0.0F,
                plateAppear, spallStarts, growths, actor.blockDistFrac, entityAppear);
        }
        if (!actor.hasPath) {
            return new Timeline(stageAge, snapshotFade(actor, stageAge), stageAlive(stageAge), false, 0.0D, 1.0D, null, Vec3.ZERO, 0.0F,
                plateAppear, spallStarts, growths, actor.blockDistFrac, entityAppear);
        }
        double progress = progressAtTime(actor, localAge / actor.flightTicks);
        Vec3 local = pathPointAt(actor, progress);
        Vec3 world = toWorld(local, partialTick);
        Vec3 dir = toWorldDir(pathDirAt(actor, progress), partialTick);
        float linger = 1.0F - (float) Mth.clamp((localAge - actor.flightTicks) / SHELL_FADE_TICKS, 0.0D, 1.0D);
        return new Timeline(stageAge, snapshotFade(actor, stageAge), stageAlive(stageAge), true, actor.flightTicks, progress,
            world, dir, linger, plateAppear, spallStarts, growths, actor.blockDistFrac, entityAppear);
    }

    private static double[] shiftArray(double[] src, double shift) {
        if (src == null || src.length == 0) return src;
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] + shift;
        return out;
    }

    private static float fadeAt(float stageAge) {
        float remaining = (float) (endTick - startTick) - stageAge;
        return Mth.clamp(remaining / (float) FADE_TICKS, 0.0F, 1.0F);
    }

    /**
     * Per-frame fade of one actor's diagram: the newest actor holds until the
     * stage end fade; an older (superseded) actor starts fading on its own
     * once its per-shell hold ({@link #actorFadeStartTick}) has elapsed, so
     * while a stream of shells keeps arriving the older marks clear instead of
     * piling up fully bright. The per-actor fade never exceeds the stage fade.
     */
    private static float snapshotFade(Actor actor, float stageAge) {
        boolean newest = actors.isEmpty() || actor == actors.get(actors.size() - 1);
        if (newest) return fadeAt(stageAge);
        float own = Mth.clamp((float) ((actorFadeStartTick(actor) + FADE_TICKS - stageAge) / (float) FADE_TICKS),
            0.0F, 1.0F);
        return Math.min(own, fadeAt(stageAge));
    }

    private static boolean stageAlive(float stageAge) {
        return stageAge < endTick - startTick;
    }

    /** World-space shell position for the current animation frame, or null when hidden. */
    @Nullable
    public static Vec3 shellPos(float partialTick) {
        Actor primary = primary();
        if (primary == null) return null;
        float stageAge = age(partialTick);
        Actor live = liveFlightActor(stageAge);
        // The live actor's own frame while its shell is on screen; otherwise the
        // primary's resting end-of-path position (the PiP streak fallback draws
        // only while a shell is actually in flight, so this is a safe default).
        Actor a = live != null ? live : primary;
        Timeline t = actorSnapshot(a, partialTick);
        return t == null ? null : t.shellPos;
    }

    /** World-space shell direction for the current animation frame. */
    public static Vec3 shellDir(float partialTick) {
        Actor primary = primary();
        if (primary == null) return Vec3.ZERO;
        float stageAge = age(partialTick);
        Actor live = liveFlightActor(stageAge);
        Actor a = live != null ? live : primary;
        Timeline t = actorSnapshot(a, partialTick);
        return t == null ? Vec3.ZERO : t.shellDir;
    }

    /** True while some actor's shell model is actually in flight (a tracer should show). */
    public static boolean liveFlightActive(float partialTick) {
        return liveFlightActor(age(partialTick)) != null;
    }

    /**
     * The ghost shell model for the replay, positioned and oriented for this
     * frame. The PRIMARY shell shows while its own flight is live; once the
     * primary has landed, the newest merged actor that is still mid-flight
     * takes over, so each arriving burst shell visibly flies in over the
     * running diagram. Returns null when no actor's shell is on screen (the
     * caller falls back to a streak / nothing).
     */
    @Nullable
    public static Entity ghost(float partialTick) {
        Actor primary = primary();
        if (primary == null) return null;
        float stageAge = age(partialTick);
        Actor live = liveFlightActor(stageAge);
        return live == null ? null : ghostShellFor(live, partialTick);
    }

    /**
     * The actor whose shell model is on screen right now: the primary while
     * its own replay flies, otherwise the newest merged actor whose flight
     * window is still open. Null when no shell is in flight.
     */
    @Nullable
    private static Actor liveFlightActor(float stageAge) {
        if (actors.isEmpty()) return null;
        Actor primary = actors.get(0);
        if (primary.hasPath && actorFlightLive(primary, stageAge)) return primary;
        for (int i = actors.size() - 1; i >= 1; i--) {
            Actor actor = actors.get(i);
            if (actor.hasPath && actorFlightLive(actor, stageAge)) return actor;
        }
        return null;
    }

    /** True while this actor's shell model is on screen (flying or in the end-of-path linger). */
    private static boolean actorFlightLive(Actor actor, float stageAge) {
        if (!actor.hasPath) return false;
        double localAge = stageAge - actor.startTick;
        if (localAge < 0.0D) return false;
        // Flight visible from 0 to flightTicks + the 4-tick alpha fade (the
        // model's shellAlpha crosses 0.05 at ~3.8 ticks past the end of the path).
        return localAge < actor.flightTicks + SHELL_FADE_TICKS;
    }

    // Ghost shell model, reused across frames and actors; recreated only when
    // the entity type of the current fly-in shell changes.
    private static Entity ghostShell;
    private static String ghostKeyUsed = "";

    /** The shell model for one actor, positioned for this frame. */
    @Nullable
    private static Entity ghostShellFor(Actor actor, float partialTick) {
        Timeline t = actorSnapshot(actor, partialTick);
        if (t == null || t.shellAlpha <= 0.05F || t.shellPos == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        String key = actor.packet.entityTypeKey();
        if (key.isEmpty()) return null;
        if (ghostShell == null || !key.equals(ghostKeyUsed)) {
            ghostKeyUsed = key;
            ghostShell = null;
            Optional<EntityType<?>> type = EntityType.byString(key);
            if (type.isPresent()) {
                try {
                    ghostShell = type.get().create(mc.level);
                } catch (Throwable ex) {
                    ghostShell = null;
                }
            }
        }
        Entity shell = ghostShell;
        if (shell == null) return null;
        shell.setPos(t.shellPos.x, t.shellPos.y, t.shellPos.z);
        shell.xOld = shell.xo = t.shellPos.x;
        shell.yOld = shell.yo = t.shellPos.y;
        shell.zOld = shell.zo = t.shellPos.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-t.shellDir.x, t.shellDir.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(t.shellDir.y, -1.0D, 1.0D)));
        shell.setYRot(yaw);
        shell.setXRot(pitch);
        shell.yRotO = yaw;
        shell.xRotO = pitch;
        ClientHitcamPiP.setGhostOrientation(shell, t.shellDir);
        return shell;
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
                primary() != null ? primary().packet.projectileId() : -1);
        }
        return vsShip;
    }

    private static void recomputeBounds() {
        Actor primary = primary();
        if (primary == null) return;
        ClientboundTerminalBallisticsPacket view = primary.packet;
        // Center on the FIRST engagement (first plate), so secondary
        // engagements farther out never pull the camera off the first hit.
        List<ClientboundTerminalBallisticsPacket.Plate> plateList = view.plates();
        if (!plateList.isEmpty()) {
            anchorCenter = plateList.get(0).pos();
        } else if (!view.spalls().isEmpty()) {
            anchorCenter = view.spalls().get(0).origin();
        } else if (view.blast() != null) {
            anchorCenter = view.blast().pos();
        } else {
            anchorCenter = Vec3.ZERO;
        }
        double maxDistSq = 0.0D;
        for (ClientboundTerminalBallisticsPacket.Plate plate : plateList) {
            maxDistSq = Math.max(maxDistSq, plate.pos().distanceToSqr(anchorCenter));
        }
        for (ClientboundTerminalBallisticsPacket.Spall cone : view.spalls()) {
            double spallDist = Math.sqrt(cone.origin().distanceToSqr(anchorCenter)) + cone.range();
            maxDistSq = Math.max(maxDistSq, spallDist * spallDist);
        }
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : view.entities()) {
            maxDistSq = Math.max(maxDistSq, hit.pos().distanceToSqr(anchorCenter));
        }
        ClientboundTerminalBallisticsPacket.Blast blast = view.blast();
        if (blast != null) {
            // The blast sphere must fit in frame, whatever it does to the rest.
            double blastDist = Math.sqrt(blast.pos().distanceToSqr(anchorCenter)) + blast.radius();
            maxDistSq = Math.max(maxDistSq, blastDist * blastDist);
        }
        // An HE detonation needs a much wider orbit than a kinetic hit for the
        // fireball to read; AP-style shells (APHE) keep the tight AP framing
        // even though their blast is drawn.
        double radiusCap = blast != null && blast.explosive() ? 24.0D : 12.0D;
        double radius = Math.max(5.5D, Math.min(radiusCap, Math.sqrt(maxDistSq) + 3.0D));
        if (blast != null && blast.explosive()) {
            // Far view: hold the camera ~30 blocks out from the detonation
            // (unless the damage diagram itself is wider).
            radius = Math.max(radius, 30.0D);
        }
        orbitRadius = radius;
    }

    private ClientTerminalView() {}
}
