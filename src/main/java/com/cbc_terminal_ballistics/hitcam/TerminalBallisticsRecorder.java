package com.cbc_terminal_ballistics.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.ProjectileClassifier;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.goggles.GoggleLinkManager;
import com.cbc_terminal_ballistics.network.ClientboundArtilleryUpdatePacket;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.network.ClientboundTrackProjectilePacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.util.CBCReflect;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import com.cbc_terminal_ballistics.util.VSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side collector behind the terminal-ballistics hitcam. While a
 * projectile works through armour it accumulates per-plate outcomes, a spall
 * summary and entity hits; when the engagement ends the whole x-ray diagram is
 * sent to the shooter's client as one packet. Sessions are keyed by the
 * projectile's UUID (it survives the chunk-unload re-creation cycle), each
 * holding a reference to the currently bound entity.
 */
public final class TerminalBallisticsRecorder {
    private static final int MAX_PLATES = 16;
    private static final int MAX_ENTITY_HITS = 8;
    private static final int MAX_SPALL_CONES = 4;
    private static final int MAX_SPALL_BLOCKS_PER_CONE = 16;
    private static final int MAX_CRITICALS = 16;
    private static final int STALE_FLUSH_TICKS = 30;
    /**
     * Max sane distance of a converted point from the shipyard anchor. A failed
     * VS conversion leaves world coordinates ~100000 blocks away; a real
     * ship-local point is the anchor plus ship size plus the flight tail.
     */
    private static final double FRAME_SANITY_LIMIT = 16384.0D;

    /**
     * Sessions keyed by the shell's UUID, not the entity object. A shell whose
     * chunk unloads (RPL ticket expiry) is re-created by vanilla with a new
     * entity id but the same UUID; a weakly-keyed map let the removed entity's
     * garbage collection silently swallow the session before the relay could
     * rebind it. Strong refs here, cleaned up on every session end path.
     */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /**
     * Explosion contexts bridge source-less explosion damage events to the
     * terminal engagement that caused them. They are deliberately short-lived:
     * proximity to an old impact alone must never turn an unrelated death into
     * a critical crew kill.
     */
    private static final List<SecondaryExplosion> SECONDARY_EXPLOSIONS = new ArrayList<>();
    private static final int SECONDARY_EXPLOSION_TICKS = 2;

    private static final class SecondaryExplosion {
        final Session session;
        final Vec3 center;
        final double radius;
        final long expiresAt;

        SecondaryExplosion(Session session, Vec3 center, double radius, long expiresAt) {
            this.session = session;
            this.center = center;
            this.radius = radius;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Session {
        /** The live projectile entity this session is currently bound to (changes on rebind). */
        Entity bound;
        final List<ClientboundTerminalBallisticsPacket.Plate> plates = new ArrayList<>();
        final List<ClientboundTerminalBallisticsPacket.EntityHit> entities = new ArrayList<>();
        /**
         * Spall cones in record order; index 0 is the main armour plate's cone
         * (the camera focus). Later cones are secondary engagements — another
         * plate, another ship — rendered unfocused after the first.
         */
        final List<ClientboundTerminalBallisticsPacket.Spall> spalls = new ArrayList<>();
        /** Spall-struck blocks per cone, parallel to {@link #spalls}; filled after each cone is recorded. */
        final List<List<ClientboundTerminalBallisticsPacket.SpallBlock>> spallBlocksPerCone = new ArrayList<>();
        /**
         * Critical-hit evaluation entries: destroyed mechanical components
         * (data-driven critical block list) and killed crew, in record order.
         */
        final List<ClientboundTerminalBallisticsPacket.CriticalEntry> criticals = new ArrayList<>();
        /** World-space block positions already recorded as criticals, for dedupe. */
        final it.unimi.dsi.fastutil.longs.LongOpenHashSet criticalPositions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        /** True once any critical event (destroyed component or crew kill) was recorded. */
        boolean hasCritical;
        UUID anchorUuid;
        /** Every player receiving this shell's track/relay/terminal views (shooter, seated gunner, goggle links). */
        final List<ServerPlayer> audiences = new ArrayList<>();
        long firstEventGameTime = -1L;
        boolean hasTerminalOutcome;
        /** Spall or the shell body reached non-armour structure behind the armour. */
        boolean internalDamage;
        /** Raw shipyard position of the first VS plate, captured before world normalization. */
        BlockPos vsAnchorPos;
        /** The shell's own detonation: world centre, radius, and whether the shell is not AP-style (HE widening signal). */
        Vec3 blastPos;
        float blastRadius;
        boolean explosiveShell;
        /** Explosive shell by the predefined name list ({@link ProjectileClassifier#isExplosiveShell}); gates blast synthesis. */
        boolean heShell;
        /** Game time of the last immediate snapshot send, for rate limiting. */
        long lastSnapshotGameTime = Long.MIN_VALUE;
        /** New events arrived since the last packet went out; gates the stale flush. */
        boolean dirty;
        /**
         * Counts every recorded plate/spall-cone/spall-block/entity; the flush
         * gates compare it against the value at the last dispatch so a
         * pass-through shell's later engagements (second ship) actually reach
         * the client instead of dying in a never-flushed session.
         */
        int dataVersion;
        /** {@link #dataVersion} at the last snapshot dispatch. */
        int dataVersionAtSnapshot;
        /** Chunks already streamed to each audience member for the artillery cam's client-side terrain. */
        final java.util.Map<java.util.UUID, it.unimi.dsi.fastutil.longs.LongOpenHashSet> streamedChunks =
            new java.util.HashMap<>();

        it.unimi.dsi.fastutil.longs.LongOpenHashSet streamedChunksFor(ServerPlayer player) {
            return streamedChunks.computeIfAbsent(player.getUUID(),
                k -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
        }
        /** Terrain-stream diagnostics: lifetime total, plus last-relay sent/unloaded counts. */
        int streamTotal;
        int streamSentLast;
        int streamNullLast;
        long lastStreamLogGameTime = -1L;
        boolean streamGapLogged;
        /** Stuck-shell watchdog: last relayed position and count of unmoved relays. */
        Vec3 lastRelayPos;
        int stuckRelays;
        /** One-time terminal path backfill for viewers that opted out of artillery streaming. */
        boolean terminalBackfill;
        /** Chunk-unload rebind: the shell's UUID while waiting for its re-created entity. */
        UUID pendingUuid;
        long pendingSinceTick = -1L;
        /**
         * Post-mortem grace window: the shell entity is gone (removed, or the
         * engagement flushed terminal) but cook-off/crew-death events may still
         * arrive for {@link #graceEndTick} ticks. The session stays registered
         * so those events update the client's verdict in place.
         */
        boolean shellGone;
        long graceEndTick = -1L;
        /** World-space position of the most recent recorded event, for post-mortem attribution. */
        Vec3 lastWorldPos;
    }

    /**
     * Coordinate frame the snapshot is stored in. Identity when there is no
     * anchor (plain world space). A contraption frame converts world points
     * with Create's toLocalVector; a VS frame converts world points into the
     * ship's shipyard coordinates and passes points that are already shipyard
     * (the mod's |x|/|z| &gt; 100000 convention) through untouched, since ship
     * block positions arrive in that space directly.
     */
    private static final class ShipFrame {
        @Nullable final ClientboundTerminalBallisticsPacket.Anchor anchor;
        @Nullable private final Entity contraption;
        @Nullable private final ServerLevel level;
        @Nullable private final BlockPos vsShipyardPos;

        private ShipFrame(@Nullable ClientboundTerminalBallisticsPacket.Anchor anchor, @Nullable Entity contraption,
                          @Nullable ServerLevel level, @Nullable BlockPos vsShipyardPos) {
            this.anchor = anchor;
            this.contraption = contraption;
            this.level = level;
            this.vsShipyardPos = vsShipyardPos;
        }

        static ShipFrame contraption(Entity contraption) {
            return new ShipFrame(
                ClientboundTerminalBallisticsPacket.Anchor.contraption(contraption.getUUID().toString()),
                contraption, null, null);
        }

        static ShipFrame vs(ServerLevel level, BlockPos shipyardPos,
                            ClientboundTerminalBallisticsPacket.Anchor anchor) {
            return new ShipFrame(anchor, null, level, shipyardPos);
        }

        static ShipFrame world() {
            return new ShipFrame(null, null, null, null);
        }

        /** World-space point -> vehicle-local. */
        Vec3 toLocal(Vec3 world) {
            if (contraption != null) return toAnchor(contraption, world);
            if (vsShipyardPos != null) {
                Vec3 local = VSCompat.toShipCoordinates(level, vsShipyardPos, world);
                return local != null ? local : world;
            }
            return world;
        }

        /** A recorded point that may already be ship-local (VS plates/spall) or world. */
        Vec3 toItemLocal(Vec3 recorded) {
            if (contraption != null) return toAnchor(contraption, recorded);
            if (vsShipyardPos != null) {
                return VSCompat.isShipyardCoordinate(recorded) ? recorded : toLocal(recorded);
            }
            return recorded;
        }

        /** World-space direction -> vehicle-local (rotation only, via two-point sampling). */
        Vec3 toLocalDir(Vec3 worldDir) {
            if (contraption != null) return toAnchorDirection(contraption, worldDir);
            if (vsShipyardPos != null) {
                Vec3 from = VSCompat.toShipCoordinates(level, vsShipyardPos, Vec3.ZERO);
                Vec3 to = VSCompat.toShipCoordinates(level, vsShipyardPos, worldDir);
                if (from != null && to != null) return to.subtract(from);
            }
            return worldDir;
        }
    }

    // ---- hooks -----------------------------------------------------------------

    /**
     * First tick of a CBC projectile: resolve the audience and start the
     * artillery cam. A UUID match with an existing session means this is the
     * same shell re-created after its chunk unloaded (new entity id, same
     * UUID): rebind the session in place — keeping its plates, terrain dedup
     * set and audience — and tell the client to follow the new id.
     */
    public static void onProjectileSpawned(Entity projectile) {
        if (!(projectile.level() instanceof ServerLevel level)) return;
        if (!TBConfig.HITCAM_ENABLED.get()) return;
        Session existing = SESSIONS.get(projectile.getUUID());
        if (existing != null && !existing.shellGone) {
            if (existing.bound == projectile) return;
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-CHUNKSTREAM] shell rebind: newId={} oldId={} uuid={}",
                projectile.getId(), existing.bound == null ? -1 : existing.bound.getId(), projectile.getUUID());
            existing.bound = projectile;
            existing.pendingUuid = null;
            existing.pendingSinceTick = -1L;
            existing.lastRelayPos = null;
            existing.stuckRelays = 0;
            if (existing.audiences.isEmpty()) {
                existing.audiences.addAll(resolveAudiences(level, projectile));
            }
            if (!existing.audiences.isEmpty() && TBConfig.HITCAM_ARTILLERY_CAM.get()) {
                sendTo(existing.audiences, new ClientboundTrackProjectilePacket(projectile.getId(), true));
            }
            return;
        }
        List<ServerPlayer> audiences = resolveAudiences(level, projectile);
        if (audiences.isEmpty()) return;
        Session session = new Session();
        session.bound = projectile;
        session.audiences.addAll(audiences);
        session.heShell = ProjectileClassifier.isExplosiveShell(projectile);
        session.explosiveShell = session.heShell && !ProjectileClassifier.isApheType(projectile);
        SESSIONS.put(projectile.getUUID(), session);
        if (TBConfig.HITCAM_ARTILLERY_CAM.get()) {
            sendTo(session.audiences, new ClientboundTrackProjectilePacket(projectile.getId(), false));
        }
    }

    /**
     * One armour plate processed by {@code TBImpactService.calculate}. Only
     * armour-like, metal, vehicle-panel (copycat/framed) or contraption blocks
     * are reported; terrain hits are ignored.
     */
    public static void onPlateImpact(Entity projectile, Vec3 worldPos, String outcome, double damage,
                                     double toughness, boolean broken, int blockStateId, float[][] shapeBoxes,
                                     @Nullable Entity contraptionEntity) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null) return;
        if (session.firstEventGameTime < 0L) session.firstEventGameTime = projectile.level().getGameTime();
        session.lastWorldPos = normalizePoint(projectile, worldPos);
        if (session.plates.size() < MAX_PLATES) {
            if (session.vsAnchorPos == null && VSCompat.isShipyardCoordinate(worldPos)) {
                session.vsAnchorPos = BlockPos.containing(worldPos);
            }
            session.plates.add(new ClientboundTerminalBallisticsPacket.Plate(
                session.lastWorldPos, outcome, damage, toughness, broken, blockStateId,
                shapeBoxes == null ? new float[0][] : shapeBoxes));
        }
        if (contraptionEntity != null && session.anchorUuid == null) session.anchorUuid = contraptionEntity.getUUID();
        if ("BOUNCE".equals(outcome)) {
            // A ricochet does not end the shell: it keeps flying and the
            // artillery relay must survive the hit. The x-ray still plays —
            // sent as a snapshot so the session stays open for the relay loop.
            sendSnapshot(projectile, session);
        } else if (!"PENETRATE".equals(outcome)) {
            session.hasTerminalOutcome = true;
        }
        session.dataVersion++;
        session.dirty = true;
        // Play the first armour hit: one snapshot when the first plate is
        // struck starts the terminal animation while the shell keeps flying.
        // Later plates only accumulate; the view restarts exactly once more,
        // when the shell finally stops or bounces (terminal flush in
        // onClipAndDamageTail), so mid-flight snapshots never cut the
        // animation short. The never-sent sentinel is compared by equality —
        // subtracting Long.MIN_VALUE would overflow. Skipped on the spawn
        // tick itself: a muzzle-area graze would snapshot a one-sample path,
        // which the client can only play as a static flash.
        if ("PENETRATE".equals(outcome)
            && session.lastSnapshotGameTime == Long.MIN_VALUE
            && projectile.tickCount >= 2) {
            sendSnapshot(projectile, session);
        }
    }

    public static void onSpall(Entity projectile, Vec3 origin, Vec3 dir, double coneCos, double range,
                               int fragments, float[] rayStops) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null || fragments <= 0) return;
        session.lastWorldPos = normalizePoint(projectile, origin);
        if (session.spalls.size() < MAX_SPALL_CONES) {
            session.spalls.add(new ClientboundTerminalBallisticsPacket.Spall(normalizePoint(projectile, origin),
                dir, coneCos, range, fragments, new ArrayList<>(),
                rayStops != null ? rayStops : new float[0]));
            session.spallBlocksPerCone.add(new ArrayList<>());
        }
        session.dataVersion++;
        session.dirty = true;
    }

    /**
     * VS ship block hits arrive in the shipyard coordinates of whichever ship
     * was hit; a session can span several ships (hull of ship A, internals of
     * ship B), so every recorded point is normalized to plain world space here
     * and only converted into the single anchor frame at finalize time.
     */
    private static Vec3 normalizePoint(Entity projectile, Vec3 recorded) {
        if (projectile.level() instanceof ServerLevel level) {
            return VSCompat.toWorldCoordinates(level, recorded);
        }
        return recorded;
    }

    public static void onSpallBlock(Entity projectile, Vec3 worldPos, boolean broken, boolean armorLike) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null || session.spallBlocksPerCone.isEmpty()) return;
        session.lastWorldPos = normalizePoint(projectile, worldPos);
        // Blocks arrive right after their own cone's onSpall, so the last
        // list is always the cone these blocks belong to.
        List<ClientboundTerminalBallisticsPacket.SpallBlock> coneBlocks =
            session.spallBlocksPerCone.get(session.spallBlocksPerCone.size() - 1);
        if (coneBlocks.size() >= MAX_SPALL_BLOCKS_PER_CONE) return;
        coneBlocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(
            normalizePoint(projectile, worldPos), broken, armorLike));
        if (!armorLike) session.internalDamage = true;
        session.dataVersion++;
        session.dirty = true;
    }

    /**
     * The shell body perforated or broke a non-armour block. Only counts as
     * internal damage once real armour was hit first, so terrain on the flight
     * path is not misread as a penetration into the hull.
     */
    public static void onInternalBlockDamage(Entity projectile) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null || session.plates.isEmpty()) return;
        session.internalDamage = true;
    }

    /**
     * A critical block was BROKEN (not merely damaged) by this shell — its
     * body, spall, or explosion. The block is checked against the data-driven
     * critical block list; hits are deduped per block position.
     */
    public static void onCriticalBlock(Entity projectile, Vec3 worldPos, net.minecraft.world.level.block.state.BlockState state) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get() || !TBConfig.HITCAM_TERMINAL_CRITICALS.get()) return;
        if (state == null || !com.cbc_terminal_ballistics.data.CriticalBlockManager.INSTANCE.isCritical(state)) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null) {
            // Not the shell's own doing (e.g. an ammo-rack cook-off explosion
            // that still carries the shell as its source chain): attribute by
            // proximity to a finished engagement's last impact.
            session = attributeByPosition(projectile.level(), worldPos);
        }
        if (session == null) return;
        Vec3 world = normalizePoint(projectile, worldPos);
        session.lastWorldPos = world;
        long packed = BlockPos.containing(world).asLong();
        if (!session.criticalPositions.add(packed)) return;
        if (session.criticals.size() < MAX_CRITICALS) {
            net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            session.criticals.add(new ClientboundTerminalBallisticsPacket.CriticalEntry(
                world, id == null ? "" : id.toString(), false));
        }
        session.hasCritical = true;
        session.dataVersion++;
        session.dirty = true;
    }

    /**
     * Bind every explosion to its live shell session, or to the nearest
     * shell-gone session when the source was lost to a cook-off. This is called
     * from ExplosionEvent.Start, before vanilla applies explosion damage and
     * therefore before any crew LivingDeathEvent can fire.
     */
    public static void onSecondaryExplosionStart(net.minecraft.world.level.Level level, @Nullable Entity source,
                                                 Vec3 center, float radius) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = source == null ? attributeByPosition(level, center) : SESSIONS.get(source.getUUID());
        if (session == null) return;
        long now = level.getGameTime();
        SECONDARY_EXPLOSIONS.removeIf(context -> context.expiresAt < now);
        SECONDARY_EXPLOSIONS.add(new SecondaryExplosion(session, center, Math.max(0.0D, radius),
            now + SECONDARY_EXPLOSION_TICKS));
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] secondary explosion bound shell={} source={} radius={} at ({},{},{})",
            session.bound == null ? -1 : session.bound.getId(), source == null ? "none" : source.getId(), radius,
            (long) Math.floor(center.x), (long) Math.floor(center.y), (long) Math.floor(center.z));
    }

    public static void onUnattributedCriticalBlock(net.minecraft.world.level.Level level, Vec3 worldPos,
                                                   net.minecraft.world.level.block.state.BlockState state) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get() || !TBConfig.HITCAM_TERMINAL_CRITICALS.get()) return;
        if (state == null || !com.cbc_terminal_ballistics.data.CriticalBlockManager.INSTANCE.isCritical(state)) return;
        Session session = attributeByPosition(level, worldPos);
        if (session == null) return;
        session.lastWorldPos = worldPos;
        long packed = BlockPos.containing(worldPos).asLong();
        if (!session.criticalPositions.add(packed)) return;
        if (session.criticals.size() < MAX_CRITICALS) {
            net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            session.criticals.add(new ClientboundTerminalBallisticsPacket.CriticalEntry(
                worldPos, id == null ? "" : id.toString(), false));
        }
        session.hasCritical = true;
        session.dataVersion++;
        session.dirty = true;
    }

    /** Max distance (blocks) from an engagement's last impact for post-mortem attribution. */
    private static final double ATTRIBUTION_RADIUS = 8.0D;

    /**
     * Find the finished engagement (shell gone, grace window still open) whose
     * last recorded event position is nearest to {@code pos}. Only shell-gone
     * sessions qualify: while the shell is alive, attribution runs through its
     * UUID and nearby unrelated deaths must never be claimed.
     */
    @Nullable
    private static Session attributeByPosition(net.minecraft.world.level.Level level, Vec3 pos) {
        if (TBConfig.HITCAM_TERMINAL_POSTHUMOUS_TICKS.get() <= 0) return null;
        Session best = null;
        double bestDistSq = ATTRIBUTION_RADIUS * ATTRIBUTION_RADIUS;
        for (Session session : SESSIONS.values()) {
            if (!session.shellGone || session.lastWorldPos == null) continue;
            if (session.bound == null || session.bound.level() != level) continue;
            double distSq = session.lastWorldPos.distanceToSqr(pos);
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = session;
            }
        }
        return best;
    }

    public static void onSpallEntityHit(Entity projectile, LivingEntity target, float damage) {
        recordEntityHit(projectile, target, damage, false);
    }

    /** Direct shell hits on living entities, reported via Forge's LivingHurtEvent. */
    public static void onDirectEntityHit(Entity projectile, LivingEntity target, float amount) {
        recordEntityHit(projectile, target, amount, false);
    }

    /** Direct kills reported via Forge's LivingDeathEvent. */
    public static void onDirectEntityKill(Entity projectile, LivingEntity target) {
        recordEntityHit(projectile, target, 0.0F, true);
    }

    /**
     * Crew death caused by a secondary explosion with no surviving source
     * entity. The explosion must have been attributed to this engagement in
     * the same tick or immediately preceding ticks; an old nearby impact is
     * insufficient on its own.
     */
    public static void onSecondaryExplosionCrewKill(net.minecraft.world.level.Level level, LivingEntity target) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        long now = level.getGameTime();
        Session session = null;
        double bestDistSq = Double.MAX_VALUE;
        for (java.util.Iterator<SecondaryExplosion> it = SECONDARY_EXPLOSIONS.iterator(); it.hasNext(); ) {
            SecondaryExplosion context = it.next();
            if (context.expiresAt < now || context.session.bound == null || context.session.bound.level() != level) {
                it.remove();
                continue;
            }
            double distSq = context.center.distanceToSqr(target.position());
            double reach = context.radius + 2.0D;
            if (distSq <= reach * reach && distSq < bestDistSq) {
                bestDistSq = distSq;
                session = context.session;
            }
        }
        if (session == null) return;
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] secondary crew kill attributed shell={} victim={} at ({},{},{})",
            session.bound.getId(), target.getId(),
            (long) Math.floor(target.getX()), (long) Math.floor(target.getY()), (long) Math.floor(target.getZ()));
        recordEntityHit(session, target, 0.0F, true);
    }

    /**
     * The shell's own detonation (fired from the vanilla-forking
     * {@code ExplosionEvent.Detonate} handler, attributed via the explosion's
     * indirect source entity). HE detonations usually happen as the shell is
     * removed, so the recorded blast reaches the client through the same
     * dirty-flush paths as criticals — typically the posthumous grace flush —
     * refreshing the actor in place. An explosive (non-AP-style) shell ALWAYS
     * yields a terminal view: the dispatch gate treats a blast like a plate.
     */
    public static void onShellExplosion(Entity projectile, Vec3 center, float radius) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null) return;
        if (session.blastPos != null) return; // first detonation wins; chain/cook-off blasts stay unattributed
        session.blastPos = normalizePoint(projectile, center);
        session.blastRadius = radius;
        session.explosiveShell = !ProjectileClassifier.isApheType(projectile);
        session.lastWorldPos = session.blastPos;
        session.dataVersion++;
        session.dirty = true;
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] shell blast recorded shell={} tick={} radius={} explosive={} at ({},{},{})",
            projectile.getId(), projectile.tickCount, radius, session.explosiveShell,
            (long) Math.floor(center.x), (long) Math.floor(center.y), (long) Math.floor(center.z));
        // Dispatch right now: a detonation is a terminal event, and the view
        // must open (or refine) at detonation time — not when the removal or
        // posthumous flush happens to run. Particularly important for bypassed
        // HEAT/HEAP shells and airbursts, whose only other flush is posthumous.
        dispatch(projectile, session);
        session.lastSnapshotGameTime = projectile.level().getGameTime();
        session.dataVersionAtSnapshot = session.dataVersion;
        session.dirty = false;
    }

    /**
     * The simplified HE pipeline: an explosive shell (predefined name list)
     * carrying a fuze whose flight ends WITHOUT a real detonation still gets a
     * blast in the x-ray — synthesized at its terminal position, sized by
     * caliber. CBC impact fuzes are probabilistic, so dud shells are common;
     * without this the HE view work had nothing to render. A real detonation
     * records first ({@link #onShellExplosion}) and wins.
     */
    private static void syntheticBlast(Session session, Entity projectile) {
        if (session.blastPos != null || !session.heShell || !CBCReflect.hasFuze(projectile)) return;
        Vec3 center = projectile.position();
        session.blastPos = normalizePoint(projectile, center);
        session.blastRadius = caliberBlastRadius(projectile);
        session.dataVersion++;
        session.dirty = true;
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] synthetic HE blast shell={} tick={} radius={} at ({},{},{})",
            projectile.getId(), projectile.tickCount, session.blastRadius,
            (long) Math.floor(center.x), (long) Math.floor(center.y), (long) Math.floor(center.z));
    }

    /** Stylized blast radius per caliber, scaled by {@code hitcamBlastRadiusScale}. */
    private static float caliberBlastRadius(Entity projectile) {
        return switch (ProjectileClassifier.classify(projectile, false)) {
            case AUTOCANNON -> 1.2F;
            case HEAVY_AUTOCANNON -> 1.5F;
            case SMALL -> 1.8F;
            case SMALL_MEDIUM -> 2.2F;
            case MEDIUM -> 3.0F;
            case BIG -> 4.5F;
        } * TBConfig.HITCAM_BLAST_RADIUS_SCALE.get().floatValue();
    }

    private static void recordEntityHit(Entity projectile, LivingEntity target, float damage, boolean killed) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null && killed) {
            // The killer entity has no session (e.g. an ammo cook-off explosion
            // killing the crew after the shell is gone): attribute the death
            // positionally to a finished engagement near the victim.
            session = attributeByPosition(projectile.level(), target.position());
        }
        if (session == null) return;
        recordEntityHit(session, target, damage, killed);
    }

    private static void recordEntityHit(Session session, LivingEntity target, float damage, boolean killed) {
        session.dataVersion++;
        session.dirty = true;
        for (int i = 0; i < session.entities.size(); i++) {
            ClientboundTerminalBallisticsPacket.EntityHit hit = session.entities.get(i);
            if (hit.entityId() == target.getId()) {
                boolean nowKilled = hit.killed() || killed;
                session.entities.set(i, new ClientboundTerminalBallisticsPacket.EntityHit(
                    hit.entityId(), hit.pos(), Math.max(hit.damage(), damage), nowKilled));
                if (killed && !hit.killed()) recordCrewCritical(session, target);
                return;
            }
        }
        if (session.entities.size() < MAX_ENTITY_HITS) {
            session.entities.add(new ClientboundTerminalBallisticsPacket.EntityHit(
                target.getId(), target.position(), damage, killed));
            if (killed) recordCrewCritical(session, target);
        }
    }

    /** A crew death (PlayerRevive downs included) is a critical event. */
    private static void recordCrewCritical(Session session, LivingEntity target) {
        if (!TBConfig.HITCAM_TERMINAL_CRITICALS.get()) return;
        if (session.criticals.size() < MAX_CRITICALS) {
            session.criticals.add(new ClientboundTerminalBallisticsPacket.CriticalEntry(
                target.position(), "", true));
        }
        session.hasCritical = true;
    }

    /**
     * End of one {@code clipAndDamage} pass. Flush immediately when the shell
     * died at the armour (STOP/BOUNCE — CBC removes it): that terminal packet
     * is the one full-engagement replay, carrying every plate and the final
     * outcome. Pass-through shells have already started the animation with
     * their first-hit snapshot; flush on staleness only when no snapshot has
     * gone out, so a quiet stretch of flight never restarts the animation.
     */
    public static void onClipAndDamageTail(Entity projectile) {
        Session session = SESSIONS.get(projectile.getUUID());
        if (session == null) return;
        boolean stale = session.firstEventGameTime >= 0L
            && projectile.level().getGameTime() - session.firstEventGameTime >= STALE_FLUSH_TICKS;
        boolean neverSnapshotted = session.lastSnapshotGameTime == Long.MIN_VALUE;
        boolean newData = session.dataVersion > session.dataVersionAtSnapshot;
        if (session.hasTerminalOutcome || (stale && session.dirty && (neverSnapshotted || newData))) {
            finalizeAndSend(projectile, session);
        }
    }

    // ---- artillery cam relay -----------------------------------------------------

    /**
     * Relay live shell positions for the artillery chase cam every other tick.
     * CBC simulates shells server-side beyond client tracking range, where the
     * vanilla tracker has already despawned the client copy; these updates let
     * the cam extrapolate through that region instead of freezing. Called from
     * the mod's ServerTickEvent handler.
     */
    public static void onServerTick(net.minecraft.server.MinecraftServer server) {
        if (!TBConfig.HITCAM_ENABLED.get()) return;
        expirePosthumousSessions(server);
        if (!TBConfig.HITCAM_ARTILLERY_CAM.get()) return;
        if (SESSIONS.isEmpty() || server.getTickCount() % 2 != 0) return;
        // Rebinds mutate Session fields only (bound/pending), never the map.
        // Session-ending paths remove the current key via it.remove() — a
        // map.remove() during iteration would corrupt the iterator's modCount
        // and crash the next loop step with ConcurrentModificationException.
        for (java.util.Iterator<java.util.Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<UUID, Session> entry = it.next();
            Session session = entry.getValue();
            // Post-mortem grace session: no relay/streaming, expiry handled above.
            if (session.shellGone) continue;
            Entity projectile = session.bound;
            if (projectile == null || session.audiences.isEmpty()) continue;
            if (projectile.isRemoved()) {
                if (projectile.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                    // The shell's chunk was expired and unloaded with the entity
                    // (RPL ticket expiry). RPL reloads the chunk within ticks and
                    // vanilla re-creates the shell from it with a new entity id
                    // and the same UUID — this is NOT the shell dying. Hold the
                    // session open and rebind when it re-appears (via
                    // onProjectileSpawned, or the UUID scan below).
                    handleChunkUnload(session, projectile, server);
                    continue;
                }
                CBCTerminalBallistics.LOGGER.info(
                    "[CTB-CHUNKSTREAM] shell {} removed server-side: reason={} at {}",
                    projectile.getId(), projectile.getRemovalReason(), projectile.position());
                // Explosive shell whose flight ended without a real detonation
                // (fuze rolled a dud, or the fuze never saw the impact):
                // synthesize the blast so the HE pipeline always renders it.
                syntheticBlast(session, projectile);
                // Pass-through terminal flush (formerly on the entity-leave
                // event, which also fires client-side): a shell that recorded
                // events but never snapshotted gets its x-ray here. A shell
                // that DID snapshot still flushes when engagements accumulated
                // since (e.g. it passed into a second ship) — otherwise that
                // data would die with the session, never reaching the client.
                if (session.dirty && session.dataVersion > session.dataVersionAtSnapshot) {
                    dispatch(projectile, session);
                    session.dataVersionAtSnapshot = session.dataVersion;
                    session.dirty = false;
                }
                sendTo(session.audiences, new ClientboundArtilleryUpdatePacket(
                    projectile.getId(), "", projectile.position(), Vec3.ZERO, true));
                // Terminal-only viewers (artillery streaming opted out) still
                // need the impact area: a shell that dies at its first plate
                // never relays again after this.
                if (projectile.level() instanceof ServerLevel removedLevel) {
                    streamTerrain(session, removedLevel, projectile.position(), Vec3.ZERO, projectile);
                }
                // Keep the session registered in its post-mortem grace window:
                // an ammo-rack cook-off or chained crew death may still follow.
                markShellGone(session, projectile.level().getGameTime());
                continue;
            }
            // Stuck-shell watchdog: a projectile that has not moved for ~5 s
            // (any jam cause) must not keep the relay, preflight and terrain
            // streaming armed forever — the client's capture window stays open
            // the whole time, and ending it later clears far-chunk caches.
            // Announce removal and drop the session instead.
            Vec3 posNow = projectile.position();
            if (session.lastRelayPos != null && posNow.distanceToSqr(session.lastRelayPos) < 1.0e-4) {
                session.stuckRelays += 2;
            } else {
                session.stuckRelays = 0;
                session.lastRelayPos = posNow;
            }
            if (session.stuckRelays >= STUCK_SESSION_RELAYS) {
                CBCTerminalBallistics.LOGGER.info(
                    "[CTB-HITCAM] session for shell {} ended: stationary {} ticks at {}",
                    projectile.getId(), session.stuckRelays, posNow);
                // Same as the removed-shell path: flush engagements recorded
                // after the last snapshot before entering the grace window.
                if (session.dirty && session.dataVersion > session.dataVersionAtSnapshot) {
                    dispatch(projectile, session);
                    session.dataVersionAtSnapshot = session.dataVersion;
                    session.dirty = false;
                }
                sendTo(session.audiences, new ClientboundArtilleryUpdatePacket(
                    projectile.getId(), "", posNow, Vec3.ZERO, true));
                if (projectile.level() instanceof ServerLevel stuckLevel) {
                    streamTerrain(session, stuckLevel, posNow, Vec3.ZERO, projectile);
                }
                markShellGone(session, projectile.level().getGameTime());
                continue;
            }
            sendTo(session.audiences, new ClientboundArtilleryUpdatePacket(
                projectile.getId(), EntityType.getKey(projectile.getType()).toString(),
                posNow, projectile.getDeltaMovement(), false));
            if (projectile.level() instanceof ServerLevel slevel) {
                Vec3 velNow = projectile.getDeltaMovement();
                preflightChunkload(slevel, posNow, velNow);
                streamTerrain(session, slevel, posNow, velNow, projectile);
            }
        }
    }

    /** Relays (each = 2 ticks) after which a stationary shell's session is ended. */
    private static final int STUCK_SESSION_RELAYS = 50;

    /** Ticks to wait for a chunk-unloaded shell to re-appear before giving up. */
    private static final int REBIND_TIMEOUT_TICKS = 200;

    /**
     * A chunk unload took the shell entity with it (RPL forced-ticket expiry);
     * the shell itself is fine — RPL still has the chunk queued, reloads it
     * within ticks, and vanilla re-creates the shell from the chunk data with
     * a new entity id and the same UUID (which re-enters through
     * {@link #onProjectileSpawned} and rebinds). Keep the relay alive across
     * the cycle: re-queue the shell's last chunk (that is what brings the
     * entity back), send frozen relay packets — the shell genuinely stops
     * ticking while its chunk is unloaded, so a frozen ghost is accurate —
     * and stream terrain for the last position.
     */
    private static void handleChunkUnload(Session session, Entity removed, net.minecraft.server.MinecraftServer server) {
        if (session.pendingUuid == null) {
            session.pendingUuid = removed.getUUID();
            session.pendingSinceTick = server.getTickCount();
            if (session.lastRelayPos == null) session.lastRelayPos = removed.position();
            session.stuckRelays = 0;
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-CHUNKSTREAM] shell {} lost to chunk unload; waiting for its re-created entity",
                removed.getId());
        }
        if (!(removed.level() instanceof ServerLevel level)) return;
        preflightChunkload(level, session.lastRelayPos, Vec3.ZERO);
        Entity reborn = findEntityByUuid(level, session.pendingUuid);
        if (reborn != null && reborn != removed && !reborn.isRemoved()) {
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-CHUNKSTREAM] shell rebind (scan): oldId={} newId={} after={} ticks",
                removed.getId(), reborn.getId(), server.getTickCount() - session.pendingSinceTick);
            session.bound = reborn;
            session.pendingUuid = null;
            session.pendingSinceTick = -1L;
            session.lastRelayPos = null;
            session.stuckRelays = 0;
            if (TBConfig.HITCAM_ARTILLERY_CAM.get()) {
                sendTo(session.audiences, new ClientboundTrackProjectilePacket(reborn.getId(), true));
            }
            return;
        }
        if (server.getTickCount() - session.pendingSinceTick >= REBIND_TIMEOUT_TICKS) {
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-CHUNKSTREAM] shell {} never re-created after chunk unload; ending session", removed.getId());
            sendTo(session.audiences, new ClientboundArtilleryUpdatePacket(
                removed.getId(), "", session.lastRelayPos, Vec3.ZERO, true));
            session.audiences.clear();
            SESSIONS.remove(session.pendingUuid);
            return;
        }
        // Frozen synthetic relay: keeps the client's ghost from timing out
        // while the chunk regenerates. The client extrapolates with the relayed
        // velocity, so zero velocity parks the ghost at the last real position.
        sendTo(session.audiences, new ClientboundArtilleryUpdatePacket(
            removed.getId(), EntityType.getKey(removed.getType()).toString(),
            session.lastRelayPos, Vec3.ZERO, false));
        streamTerrain(session, level, session.lastRelayPos, Vec3.ZERO, session.bound != null ? session.bound : removed);
    }

    /** Per relay tick cap, so a fast shell cannot flood the client with chunk packets. */
    private static final int TERRAIN_STREAM_BUDGET_PER_RELAY = 16;
    /** Cached handle for RPL's {@code queueForceLoad} — resolved once, null if unavailable. */
    private static volatile java.lang.reflect.Method rplQueueForceLoad;
    /** One-shot warning when RPL's chunk loader cannot be reached reflectively. */
    private static boolean preflightReflectionWarned;

    /**
     * Preflight the shell's path through CBC's own projectile chunk loader.
     * CBC queues only the shell's current chunk each tick and RPL unloads
     * chunks after a few ticks (default age 3), so a fast shell constantly
     * arrives at a not-yet-generated chunk and freezes there until generation
     * catches up — the "holes" that stop the shell (and the artillery cam with
     * it). Queueing the chunks just ahead of the shell, refreshed every relay
     * tick, loads them before the shell arrives. Called reflectively because
     * RitchiesProjectileLib is not on this mod's compile classpath.
     */
    private static void preflightChunkload(ServerLevel level, net.minecraft.world.phys.Vec3 pos, net.minecraft.world.phys.Vec3 vel) {
        if (!TBConfig.HITCAM_PREFLIGHT_CHUNKLOAD.get()) return;
        java.lang.reflect.Method queue = rplQueueForceLoad;
        if (queue == null) {
            try {
                queue = Class.forName("rbasamoyai.ritchiesprojectilelib.RitchiesProjectileLib")
                    .getMethod("queueForceLoad", ServerLevel.class, int.class, int.class);
                rplQueueForceLoad = queue;
            } catch (ReflectiveOperationException ex) {
                rplQueueForceLoad = null;
                if (!preflightReflectionWarned) {
                    preflightReflectionWarned = true;
                    CBCTerminalBallistics.LOGGER.warn(
                        "[CTB-CHUNKSTREAM] RPL queueForceLoad not resolvable; preflight chunk loading disabled", ex);
                }
                return;
            }
        }
        // The shell's own chunk plus the two chunks it is most likely to enter
        // next. RPL re-queueing refreshes the age timer, so refreshing every
        // relay tick keeps them loaded as long as RPL's age setting is >= 2.
        long[] chunks = new long[3];
        int count = 0;
        chunks[count++] = new ChunkPos(net.minecraft.core.BlockPos.containing(pos)).toLong();
        for (int i = 1; i <= 2; i++) {
            net.minecraft.world.phys.Vec3 ahead = pos.add(vel.scale(i * 3));
            long packed = new ChunkPos(net.minecraft.core.BlockPos.containing(ahead)).toLong();
            boolean duplicate = false;
            for (int j = 0; j < count; j++) {
                if (chunks[j] == packed) { duplicate = true; break; }
            }
            if (!duplicate) chunks[count++] = packed;
        }
        try {
            for (int i = 0; i < count; i++) {
                queue.invoke(null, level, ChunkPos.getX(chunks[i]), ChunkPos.getZ(chunks[i]));
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // Loading shells is CBC's business anyway; a failed preflight just
            // reintroduces the vanilla CBC hitching behaviour.
        }
    }

    /**
     * Stream the chunks around (and, predictively, ahead of) the shell to the
     * artillery cam's viewer. CBC's projectile chunk loading
     * ({@code projectilesCanChunkload} / RitchiesProjectileLib) keeps chunks
     * loaded on the <b>server</b> only; vanilla never sends chunks beyond the
     * player's view distance, and the client discards out-of-range chunk
     * packets, so past the render distance boundary the PiP world pass has no
     * terrain to draw. Sending the shell-neighbourhood chunks directly via
     * {@link ServerPlayer#trackChunk}, paired with the client-side retention in
     * {@code ClientChunkCacheMixin}, gives the nested render real terrain all
     * the way to impact. Chunks the server has not loaded yet (too far ahead)
     * are skipped; CBC force-loads each shell chunk just before the shell
     * enters it, at which point this relay picks it up.
     */
    private static void streamTerrain(Session session, ServerLevel level, net.minecraft.world.phys.Vec3 pos,
                                      net.minecraft.world.phys.Vec3 vel, Entity projectile) {
        // Per-view preference gating: artillery-pref viewers always get the
        // shell's surroundings; terminal-pref viewers get them once the shell
        // has terminal content (a recorded plate or blast) — the x-ray is
        // exempt from the artillery streaming switch. A shell with no
        // engagement keeps honouring artillery-only prefs, preserving the
        // fast-flight bandwidth saving.
        boolean terminalEngaged = !session.plates.isEmpty() || session.blastPos != null;
        for (ServerPlayer player : session.audiences) {
            boolean artillery = StreamPreferences.wantsArtillery(player);
            if (!artillery && !(terminalEngaged && StreamPreferences.wantsTerminal(player))) continue;
            streamTerrainFor(session, level, pos, vel, player);
        }
        if (terminalEngaged) backfillTerminalPath(session, level, projectile);
    }

    /**
     * One-time backfill of the recent flight path for viewers that opted out
     * of artillery streaming: walks the recorded flight backwards up to the
     * configured path length, streaming radius-1 chunks along it so the
     * x-ray's fly-in has terrain beneath it. Per-viewer dedup sets make a
     * repeat harmless; the flag keeps it a single pass.
     */
    private static final int TERMINAL_BACKFILL_BUDGET = 48;

    private static void backfillTerminalPath(Session session, ServerLevel level, Entity projectile) {
        if (session.terminalBackfill) return;
        session.terminalBackfill = true;
        List<HitcamSample> samples = HitcamRecorder.samplesOf(projectile);
        if (samples.isEmpty()) return;
        java.util.ArrayList<Vec3> points = new java.util.ArrayList<>();
        double budget = TBConfig.HITCAM_TERMINAL_PATH_BLOCKS.get();
        double walked = 0.0D;
        Vec3 previous = null;
        for (int i = samples.size() - 1; i >= 0 && walked < budget; i--) {
            HitcamSample sample = samples.get(i);
            Vec3 point = new Vec3(sample.x(), sample.y(), sample.z());
            if (previous == null) {
                points.add(point);
            } else {
                double d = previous.distanceTo(point);
                if (d >= 8.0D) {
                    points.add(point);
                    walked += d;
                }
            }
            previous = point;
        }
        if (points.size() <= 1) return;
        for (ServerPlayer player : session.audiences) {
            // Artillery-pref viewers already received the path as it flew.
            if (StreamPreferences.wantsArtillery(player) || !StreamPreferences.wantsTerminal(player)) continue;
            int sent = 0;
            for (Vec3 point : points) {
                if (sent >= TERMINAL_BACKFILL_BUDGET) break;
                sent += streamTerrainFor(session, level, point, Vec3.ZERO, player, 1);
            }
        }
    }

    private static void streamTerrainFor(Session session, ServerLevel level, net.minecraft.world.phys.Vec3 pos,
                                         net.minecraft.world.phys.Vec3 vel, ServerPlayer player) {
        streamTerrainFor(session, level, pos, vel, player, -1);
    }

    /** radiusOverride &lt; 0 uses the configured streaming radius. Returns chunks sent. */
    private static int streamTerrainFor(Session session, ServerLevel level, net.minecraft.world.phys.Vec3 pos,
                                        net.minecraft.world.phys.Vec3 vel, ServerPlayer player, int radiusOverride) {
        if (player.level() != level) return 0;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet streamed = session.streamedChunksFor(player);
        net.minecraft.server.level.ServerChunkCache chunks = level.getChunkSource();
        int radius = radiusOverride >= 0
            ? net.minecraft.util.Mth.clamp(radiusOverride, 1, 4)
            : net.minecraft.util.Mth.clamp(TBConfig.HITCAM_TERRAIN_STREAM_RADIUS.get(), 1, 4);
        int lead = radiusOverride >= 0 ? 0
            : net.minecraft.util.Mth.clamp(TBConfig.HITCAM_TERRAIN_STREAM_LEAD_TICKS.get(), 0, 40);

        ChunkPos center = new ChunkPos(net.minecraft.core.BlockPos.containing(pos));
        it.unimi.dsi.fastutil.longs.LongOpenHashSet candidates = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                candidates.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
        }
        for (int t = 10; t <= lead; t += 5) {
            net.minecraft.world.phys.Vec3 ahead = pos.add(vel.scale(t));
            ChunkPos cp = new ChunkPos(net.minecraft.core.BlockPos.containing(ahead));
            candidates.add(cp.toLong());
        }

        int budget = TERRAIN_STREAM_BUDGET_PER_RELAY;
        // Near-camera chunks first: a fast shell outruns the budget, so the
        // PiP camera's immediate neighbourhood must win over the ring's far
        // corners (hash-set iteration order would otherwise be arbitrary).
        long[] order = candidates.toLongArray();
        it.unimi.dsi.fastutil.longs.LongArrays.unstableSort(order, (a, b) -> {
            int da = Math.max(Math.abs(ChunkPos.getX(a) - center.x), Math.abs(ChunkPos.getZ(a) - center.z));
            int db = Math.max(Math.abs(ChunkPos.getX(b) - center.x), Math.abs(ChunkPos.getZ(b) - center.z));
            return Integer.compare(da, db);
        });
        int sentLast = 0;
        int nullLast = 0;
        for (long packed : order) {
            if (streamed.contains(packed)) continue;
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            net.minecraft.world.level.chunk.LevelChunk chunk = chunks.getChunkNow(cx, cz);
            if (chunk == null) {
                nullLast++;
                continue;
            }
            player.trackChunk(new ChunkPos(cx, cz),
                new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(
                    chunk, chunks.getLightEngine(), null, null));
            streamed.add(packed);
            sentLast++;
            if (--budget <= 0) break;
        }
        session.streamTotal += sentLast;
        session.streamSentLast = sentLast;
        session.streamNullLast = nullLast;
        if (nullLast > 0 && !session.streamGapLogged) {
            // Far impacts sit beyond the integrated/dedicated server's
            // simulation distance: getChunkNow has nothing to send, so the
            // PiP's world view there falls back to Distant Horizons LODs.
            session.streamGapLogged = true;
            CBCTerminalBallistics.LOGGER.info(
                "[CTB-CHUNKSTREAM] {} chunk(s) around the shell are not loaded server-side (simulation distance) — nothing to stream for them",
                nullLast);
        }
        if (level.getGameTime() - session.lastStreamLogGameTime >= 40) {
            session.lastStreamLogGameTime = level.getGameTime();
            CBCTerminalBallistics.LOGGER.debug(
                "[CTB-CHUNKSTREAM] server stream: shell=[{},{}] total={} sentLast={} nullLast={} pending={}",
                center.x, center.z, session.streamTotal, sentLast, nullLast, session.pendingUuid != null);
        }
        return sentLast;
    }

    // ---- finalize ----------------------------------------------------------------

    private static void finalizeAndSend(Entity projectile, Session session) {
        dispatch(projectile, session);
        session.dataVersionAtSnapshot = session.dataVersion;
        session.dirty = false;
        // Keep the session in its post-mortem grace window so cook-off
        // criticals still reach the client (0-tick grace removes immediately).
        markShellGone(session, projectile.level().getGameTime());
    }

    /**
     * Enter the post-mortem grace window: the shell is gone but ammo cook-off
     * explosions and chained crew deaths may follow seconds later. With the
     * grace disabled (or expired, see {@link #expirePosthumousSessions}) the
     * session is dropped immediately.
     */
    private static void markShellGone(Session session, long gameTime) {
        session.shellGone = true;
        session.lastRelayPos = null;
        session.stuckRelays = 0;
        int grace = TBConfig.HITCAM_TERMINAL_POSTHUMOUS_TICKS.get();
        session.graceEndTick = grace > 0 ? gameTime + grace : -1L;
        if (grace <= 0) dropSession(session);
    }

    /** Remove a grace session's registration (used once its window is spent). */
    private static void dropSession(Session session) {
        SESSIONS.entrySet().removeIf(e -> e.getValue() == session);
    }

    /**
     * End-of-grace sweep. A session that accumulated post-mortem criticals
     * since its last dispatch sends one final packet (the client's same-shell
     * refresh updates the verdict in place — NO PENETRATION becomes CRITICAL
     * HIT — or restarts the view if it already closed).
     */
    private static void expirePosthumousSessions(net.minecraft.server.MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        for (java.util.Iterator<java.util.Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Session session = it.next().getValue();
            if (!session.shellGone) continue;
            Entity bound = session.bound;
            long now = bound != null ? bound.level().getGameTime() : server.overworld() != null ? server.overworld().getGameTime() : Long.MAX_VALUE;
            if (now < session.graceEndTick) continue;
            if (session.dirty && session.dataVersion > session.dataVersionAtSnapshot
                && !session.audiences.isEmpty() && bound != null) {
                CBCTerminalBallistics.LOGGER.info(
                    "[CTB-HITCAM] posthumous criticals flush: shell={} criticals={}",
                    bound.getId(), session.criticals.size());
                dispatch(bound, session);
            }
            it.remove();
        }
    }

    /**
     * Send the current x-ray state without ending the session: called the
     * moment a plate is struck mid-flight so the terminal view starts at the
     * first armour contact, and again (rate-limited) when later plates are
     * hit, each carrying every plate recorded so far.
     */
    private static void sendSnapshot(Entity projectile, Session session) {
        dispatch(projectile, session);
        session.lastSnapshotGameTime = projectile.level().getGameTime();
        session.dataVersionAtSnapshot = session.dataVersion;
        session.dirty = false;
    }

    /** Build and send the terminal packet for the session's current state. */
    private static void dispatch(Entity projectile, Session session) {
        // An explosive shell that detonated ALWAYS yields a terminal view,
        // plate or no plate — the blast alone is worth framing.
        if ((session.plates.isEmpty() && session.blastPos == null) || session.audiences.isEmpty()) return;
        if (!(projectile.level() instanceof ServerLevel level)) return;

        ShipFrame frame = resolveFrame(level, session);

        List<HitcamSample> rawPath = HitcamRecorder.samplesOf(projectile);

        // Frame sanity: if the shipyard conversion silently failed, the points
        // stay in world coordinates — ~100000 blocks from the shipyard anchor —
        // while the packet still claims a VS anchor, and the client would place
        // the entire diagram (and camera) in the void. Downgrade to a plain
        // world anchor instead; the points are already consistent world coords.
        if (frame.vsShipyardPos != null) {
            double limitSq = FRAME_SANITY_LIMIT * FRAME_SANITY_LIMIT;
            Vec3 anchorCenterLocal = Vec3.atCenterOf(frame.vsShipyardPos);
            Vec3 probePlate = session.plates.isEmpty()
                ? session.blastPos
                : session.plates.get(0).pos();
            probePlate = frame.toItemLocal(probePlate);
            boolean plateSane = probePlate.distanceToSqr(anchorCenterLocal) <= limitSq;
            boolean pathSane = true;
            if (!rawPath.isEmpty()) {
                HitcamSample last = rawPath.get(rawPath.size() - 1);
                Vec3 probePath = frame.toLocal(new Vec3(last.x(), last.y(), last.z()));
                pathSane = probePath.distanceToSqr(anchorCenterLocal) <= limitSq;
            }
            if (!plateSane || !pathSane) {
                CBCTerminalBallistics.LOGGER.warn(
                    "[CTB-HITCAM] ship frame conversion failed (plateSane={} pathSane={}) — falling back to world frame",
                    plateSane, pathSane);
                frame = ShipFrame.world();
            }
        }

        List<HitcamSample> path = new ArrayList<>(rawPath.size());
        for (HitcamSample sample : rawPath) {
            path.add(anchoredSample(frame, sample));
        }

        List<ClientboundTerminalBallisticsPacket.Plate> plates = new ArrayList<>(session.plates.size());
        for (ClientboundTerminalBallisticsPacket.Plate plate : session.plates) {
            plates.add(new ClientboundTerminalBallisticsPacket.Plate(frame.toItemLocal(plate.pos()),
                plate.outcome(), plate.damage(), plate.toughness(), plate.broken(), plate.blockStateId(),
                plate.shapeBoxes()));
        }

        List<ClientboundTerminalBallisticsPacket.Spall> spalls = new ArrayList<>(session.spalls.size());
        for (int i = 0; i < session.spalls.size(); i++) {
            ClientboundTerminalBallisticsPacket.Spall converted = session.spalls.get(i);
            List<ClientboundTerminalBallisticsPacket.SpallBlock> coneBlocks = session.spallBlocksPerCone.get(i);
            List<ClientboundTerminalBallisticsPacket.SpallBlock> blocks = new ArrayList<>(coneBlocks.size());
            for (ClientboundTerminalBallisticsPacket.SpallBlock block : coneBlocks) {
                blocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(
                    frame.toItemLocal(block.pos()), block.broken(), block.armorLike()));
            }
            spalls.add(new ClientboundTerminalBallisticsPacket.Spall(frame.toItemLocal(converted.origin()),
                frame.toLocalDir(converted.dir()), converted.coneCos(), converted.range(),
                converted.fragments(), blocks, converted.rayStops()));
        }

        List<ClientboundTerminalBallisticsPacket.EntityHit> entities = new ArrayList<>(session.entities.size());
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : session.entities) {
            entities.add(new ClientboundTerminalBallisticsPacket.EntityHit(
                hit.entityId(), frame.toLocal(hit.pos()), hit.damage(), hit.killed()));
        }

        List<ClientboundTerminalBallisticsPacket.CriticalEntry> criticals = new ArrayList<>(session.criticals.size());
        for (ClientboundTerminalBallisticsPacket.CriticalEntry entry : session.criticals) {
            criticals.add(new ClientboundTerminalBallisticsPacket.CriticalEntry(
                frame.toLocal(entry.pos()), entry.blockId(), entry.crew()));
        }

        ClientboundTerminalBallisticsPacket.Blast blast = session.blastPos == null ? null
            : new ClientboundTerminalBallisticsPacket.Blast(
                frame.toItemLocal(session.blastPos), session.blastRadius, session.explosiveShell);

        int totalSpallFragments = 0;
        for (ClientboundTerminalBallisticsPacket.Spall cone : spalls) totalSpallFragments += cone.fragments();
        String summary = buildSummary(session);
        Vec3 plate0 = plates.isEmpty() ? blast.pos() : plates.get(0).pos();
        CBCTerminalBallistics.LOGGER.debug(
            "[CTB-HITCAM] dispatch shell={} tick={} anchor={} pathPts={} plates={} spalls={} spallFragments={} entities={} criticals={} plate0=({},{},{}) blast={} summary=\"{}\"",
            projectile.getId(), projectile.tickCount,
            frame.anchor == null ? "world" : frame.anchor.kind(),
            path.size(), plates.size(), spalls.size(), totalSpallFragments, entities.size(), criticals.size(),
            (long) Math.floor(plate0.x), (long) Math.floor(plate0.y), (long) Math.floor(plate0.z),
            blast == null ? "none" : String.format("r=%.1f,explosive=%s", blast.radius(), blast.explosive()),
            summary);
        sendTo(session.audiences, new ClientboundTerminalBallisticsPacket(
            projectile.getId(), EntityType.getKey(projectile.getType()).toString(),
            summary, frame.anchor, path, plates, spalls, entities, criticals, blast));
    }

    /**
     * Choose the coordinate frame for the snapshot. A Create contraption anchor
     * wins when the session resolved one. Otherwise the frame is the ship whose
     * shipyard plate was recorded first (points were normalized to world space
     * at record time, so a shell that continued into a second ship still maps
     * into this one frame). Falls back to plain world space.
     */
    private static ShipFrame resolveFrame(ServerLevel level, Session session) {
        if (session.anchorUuid != null) {
            Entity anchor = findEntityByUuid(level, session.anchorUuid);
            if (anchor != null) {
                return ShipFrame.contraption(anchor);
            }
        }
        if (session.vsAnchorPos != null) {
            Vec3 shipyardCenter = Vec3.atCenterOf(session.vsAnchorPos);
            Vec3 worldRef = VSCompat.toWorldCoordinates(level, shipyardCenter);
            if (worldRef != null && worldRef.distanceToSqr(shipyardCenter) > 1.0D) {
                return ShipFrame.vs(level, session.vsAnchorPos,
                    ClientboundTerminalBallisticsPacket.Anchor.vs(shipyardCenter, worldRef));
            }
        }
        return ShipFrame.world();
    }

    private static String buildSummary(Session session) {
        // Plate/crew counts were dropped: the status line overflowed the PiP
        // window, and the top-left critical list already covers the details.
        StringBuilder sb = new StringBuilder(session.hasCritical ? "CRITICAL HIT" : classify(session));
        if (!session.spalls.isEmpty()) {
            int fragments = 0;
            for (ClientboundTerminalBallisticsPacket.Spall cone : session.spalls) fragments += cone.fragments();
            sb.append(" \u00b7 ").append(fragments).append(" spall");
        }
        return sb.toString();
    }

    /**
     * Three-way engagement classification: HIT when anything behind the armour
     * was damaged (spall into internals, the shell body punching through
     * non-armour structure, or any entity hit); RICOCHET when the shell bounced
     * off armour without such damage; NO PENETRATION when only armour-classified
     * blocks were hit, even if several layers were perforated. A shell that
     * only detonated (no plates — bypassed HEAT/HEAP, airbursts, terrain hits)
     * reads DETONATION instead of the misleading NO PENETRATION.
     */
    private static String classify(Session session) {
        if (session.internalDamage || !session.entities.isEmpty()) return "HIT";
        if (session.plates.isEmpty()) {
            return session.blastPos != null ? "DETONATION" : "NO PENETRATION";
        }
        for (ClientboundTerminalBallisticsPacket.Plate plate : session.plates) {
            if ("BOUNCE".equals(plate.outcome())) return "RICOCHET";
        }
        return "NO PENETRATION";
    }

    private static HitcamSample anchoredSample(ShipFrame frame, HitcamSample sample) {
        Vec3 local = frame.toLocal(new Vec3(sample.x(), sample.y(), sample.z()));
        Vec3 localDir = frame.toLocalDir(new Vec3(sample.ox(), sample.oy(), sample.oz()));
        if (localDir.lengthSqr() < 1.0e-8D) localDir = new Vec3(sample.ox(), sample.oy(), sample.oz());
        localDir = localDir.normalize();
        return new HitcamSample(local.x, local.y, local.z, (float) localDir.x, (float) localDir.y, (float) localDir.z);
    }

    @Nullable
    private static Vec3 toAnchor(@Nullable Entity anchor, Vec3 world) {
        if (anchor == null) return world;
        Vec3 local = ContraptionCompat.toLocalVector(anchor, world, 0.0F);
        return local != null ? local : world;
    }

    private static Vec3 toAnchorDirection(@Nullable Entity anchor, Vec3 worldDir) {
        if (anchor == null) return worldDir;
        Vec3 from = toAnchor(anchor, Vec3.ZERO);
        Vec3 to = toAnchor(anchor, worldDir);
        return to.subtract(from);
    }

    /** Max sane distance of a seated gunner's cannon from a shell spawn to count as the firer. */
    private static final double GUNNER_MATCH_RADIUS = 16.0D;

    /**
     * Everyone who should watch this shell. CBC projectiles carry no shooter
     * identity, so in order: a vanilla projectile owner (vanilla bows etc.),
     * the gunner seated on a cannon contraption near the muzzle, and every
     * player carrying ballistic goggles linked to the firing mount (or
     * spotter goggles, the opt-in nearby-shells pass). The old
     * nearest-player-within-32-blocks fallback is gone — it leaked other
     * players' shells into bystanders' views.
     */
    private static List<ServerPlayer> resolveAudiences(ServerLevel level, Entity projectile) {
        java.util.LinkedHashSet<ServerPlayer> out = new java.util.LinkedHashSet<>();
        if (projectile instanceof net.minecraft.world.entity.projectile.Projectile p
            && p.getOwner() instanceof ServerPlayer owner) {
            out.add(owner);
        }
        if (out.isEmpty()) {
            ServerPlayer gunner = nearestSeatedGunner(level, projectile.position());
            if (gunner != null) out.add(gunner);
        }
        for (ServerPlayer viewer : GoggleLinkManager.goggleAudiences(level, projectile.position())) {
            out.add(viewer);
        }
        int cap = Math.max(1, TBConfig.HITCAM_MAX_AUDIENCES.get());
        List<ServerPlayer> audiences = new ArrayList<>(Math.min(out.size(), cap));
        for (ServerPlayer player : out) {
            if (audiences.size() >= cap) break;
            audiences.add(player);
        }
        return audiences;
    }

    /**
     * The player operating a cannon (sitting on a CBC pitch-oriented
     * contraption or a cannon carriage) whose vehicle is nearest the shell
     * spawn. Class names are matched by string per the codebase convention of
     * never importing CBC classes.
     */
    private static ServerPlayer nearestSeatedGunner(ServerLevel level, Vec3 spawn) {
        ServerPlayer nearest = null;
        double best = GUNNER_MATCH_RADIUS * GUNNER_MATCH_RADIUS;
        for (Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) continue;
            Entity vehicle = player.getRootVehicle();
            if (vehicle == null || vehicle == player) continue;
            String vehicleClass = vehicle.getClass().getName();
            if (!vehicleClass.contains("PitchOrientedContraptionEntity")
                && !vehicleClass.contains("CannonCarriageEntity")) continue;
            double distSq = vehicle.distanceToSqr(spawn);
            if (distSq <= best) {
                best = distSq;
                nearest = serverPlayer;
            }
        }
        return nearest;
    }

    private static Entity findEntityByUuid(ServerLevel level, UUID uuid) {
        for (Entity entity : level.getEntities().getAll()) {
            if (entity.getUUID().equals(uuid)) return entity;
        }
        return null;
    }

    private static void send(ServerPlayer player, Object packet) {
        if (TBNetwork.CHANNEL != null && player.connection != null) {
            TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private static void sendTo(List<ServerPlayer> players, Object packet) {
        if (TBNetwork.CHANNEL == null) return;
        for (ServerPlayer player : players) {
            if (player.connection != null) {
                TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        }
    }

    private TerminalBallisticsRecorder() {}
}
