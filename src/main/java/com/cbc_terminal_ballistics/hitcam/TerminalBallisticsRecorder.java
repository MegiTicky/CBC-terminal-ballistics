package com.cbc_terminal_ballistics.hitcam;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.network.ClientboundArtilleryUpdatePacket;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.network.ClientboundTrackProjectilePacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import com.cbc_terminal_ballistics.util.VSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkDirection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.UUID;

/**
 * Server-side collector behind the terminal-ballistics hitcam. While a
 * projectile works through armour it accumulates per-plate outcomes, a spall
 * summary and entity hits; when the engagement ends the whole x-ray diagram is
 * sent to the shooter's client as one packet. Sessions are keyed weakly by the
 * projectile entity, so they vanish with it if never finalized.
 */
public final class TerminalBallisticsRecorder {
    private static final int MAX_PLATES = 16;
    private static final int MAX_ENTITY_HITS = 8;
    private static final int MAX_SPALL_BLOCKS = 16;
    private static final int STALE_FLUSH_TICKS = 30;
    private static final double AUDIENCE_SEARCH_RADIUS = 32.0D;

    private static final WeakHashMap<Entity, Session> SESSIONS = new WeakHashMap<>();

    private static final class Session {
        final List<ClientboundTerminalBallisticsPacket.Plate> plates = new ArrayList<>();
        final List<ClientboundTerminalBallisticsPacket.EntityHit> entities = new ArrayList<>();
        final List<ClientboundTerminalBallisticsPacket.SpallBlock> spallBlocks = new ArrayList<>();
        ClientboundTerminalBallisticsPacket.Spall spall;
        UUID anchorUuid;
        ServerPlayer audience;
        long firstEventGameTime = -1L;
        boolean hasTerminalOutcome;
        /** Spall or the shell body reached non-armour structure behind the armour. */
        boolean internalDamage;
        /** Raw shipyard position of the first VS plate, captured before world normalization. */
        BlockPos vsAnchorPos;
        /** Game time of the last immediate snapshot send, for rate limiting. */
        long lastSnapshotGameTime = Long.MIN_VALUE;
        /** New events arrived since the last packet went out; gates the stale flush. */
        boolean dirty;
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

    /** First tick of a CBC projectile: resolve the audience and start the artillery cam. */
    public static void onProjectileSpawned(Entity projectile) {
        if (!(projectile.level() instanceof ServerLevel level)) return;
        if (!TBConfig.HITCAM_ENABLED.get()) return;
        ServerPlayer audience = resolveAudience(level, projectile);
        if (audience == null) return;
        SESSIONS.computeIfAbsent(projectile, e -> new Session()).audience = audience;
        if (TBConfig.HITCAM_ARTILLERY_CAM.get()) {
            send(audience, new ClientboundTrackProjectilePacket(projectile.getId()));
        }
    }

    /**
     * One armour plate processed by {@code TBImpactService.calculate}. Only
     * armour-like, metal, vehicle-panel (copycat/framed) or contraption blocks
     * are reported; terrain hits are ignored.
     */
    public static void onPlateImpact(Entity projectile, Vec3 worldPos, String outcome, double damage,
                                     double toughness, boolean broken, int blockStateId,
                                     @Nullable Entity contraptionEntity) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null) return;
        if (session.firstEventGameTime < 0L) session.firstEventGameTime = projectile.level().getGameTime();
        if (session.plates.size() < MAX_PLATES) {
            if (session.vsAnchorPos == null && VSCompat.isShipyardCoordinate(worldPos)) {
                session.vsAnchorPos = BlockPos.containing(worldPos);
            }
            session.plates.add(new ClientboundTerminalBallisticsPacket.Plate(
                normalizePoint(projectile, worldPos), outcome, damage, toughness, broken, blockStateId));
        }
        if (contraptionEntity != null && session.anchorUuid == null) session.anchorUuid = contraptionEntity.getUUID();
        if (!"PENETRATE".equals(outcome)) session.hasTerminalOutcome = true;
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
        Session session = SESSIONS.get(projectile);
        if (session == null || fragments <= 0) return;
        session.spall = new ClientboundTerminalBallisticsPacket.Spall(normalizePoint(projectile, origin),
            dir, coneCos, range, fragments, session.spallBlocks,
            rayStops != null ? rayStops : new float[0]);
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
        Session session = SESSIONS.get(projectile);
        if (session == null || session.spallBlocks.size() >= MAX_SPALL_BLOCKS) return;
        session.spallBlocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(
            normalizePoint(projectile, worldPos), broken, armorLike));
        if (!armorLike) session.internalDamage = true;
        session.dirty = true;
    }

    /**
     * The shell body perforated or broke a non-armour block. Only counts as
     * internal damage once real armour was hit first, so terrain on the flight
     * path is not misread as a penetration into the hull.
     */
    public static void onInternalBlockDamage(Entity projectile) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null || session.plates.isEmpty()) return;
        session.internalDamage = true;
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

    private static void recordEntityHit(Entity projectile, LivingEntity target, float damage, boolean killed) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null) return;
        session.dirty = true;
        for (int i = 0; i < session.entities.size(); i++) {
            ClientboundTerminalBallisticsPacket.EntityHit hit = session.entities.get(i);
            if (hit.entityId() == target.getId()) {
                session.entities.set(i, new ClientboundTerminalBallisticsPacket.EntityHit(
                    hit.entityId(), hit.pos(), Math.max(hit.damage(), damage), hit.killed() || killed));
                return;
            }
        }
        if (session.entities.size() < MAX_ENTITY_HITS) {
            session.entities.add(new ClientboundTerminalBallisticsPacket.EntityHit(
                target.getId(), target.position(), damage, killed));
        }
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
        Session session = SESSIONS.get(projectile);
        if (session == null) return;
        boolean stale = session.firstEventGameTime >= 0L
            && projectile.level().getGameTime() - session.firstEventGameTime >= STALE_FLUSH_TICKS;
        boolean neverSnapshotted = session.lastSnapshotGameTime == Long.MIN_VALUE;
        if (session.hasTerminalOutcome || (stale && session.dirty && neverSnapshotted)) {
            finalizeAndSend(projectile, session);
        }
    }

    /** Projectile left the level: final flush for pass-through shells. */
    public static void onEntityRemoved(Entity projectile) {
        Session session = SESSIONS.remove(projectile);
        if (session != null && session.dirty && session.lastSnapshotGameTime == Long.MIN_VALUE) {
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
        if (!TBConfig.HITCAM_ENABLED.get() || !TBConfig.HITCAM_ARTILLERY_CAM.get()) return;
        if (SESSIONS.isEmpty() || server.getTickCount() % 2 != 0) return;
        for (java.util.Map.Entry<Entity, Session> entry : SESSIONS.entrySet()) {
            Entity projectile = entry.getKey();
            Session session = entry.getValue();
            if (session.audience == null) continue;
            if (projectile.isRemoved()) {
                send(session.audience, new ClientboundArtilleryUpdatePacket(
                    projectile.getId(), "", projectile.position(), Vec3.ZERO, true));
                session.audience = null;
                continue;
            }
            send(session.audience, new ClientboundArtilleryUpdatePacket(
                projectile.getId(), EntityType.getKey(projectile.getType()).toString(),
                projectile.position(), projectile.getDeltaMovement(), false));
        }
    }

    // ---- finalize ----------------------------------------------------------------

    private static void finalizeAndSend(Entity projectile, Session session) {
        SESSIONS.remove(projectile);
        dispatch(projectile, session);
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
        session.dirty = false;
    }

    /** Build and send the terminal packet for the session's current state. */
    private static void dispatch(Entity projectile, Session session) {
        if (session.plates.isEmpty() || session.audience == null) return;
        if (!(projectile.level() instanceof ServerLevel level)) return;

        ShipFrame frame = resolveFrame(level, session);

        List<HitcamSample> rawPath = HitcamRecorder.samplesOf(projectile);
        List<HitcamSample> path = new ArrayList<>(rawPath.size());
        for (HitcamSample sample : rawPath) {
            path.add(anchoredSample(frame, sample));
        }

        List<ClientboundTerminalBallisticsPacket.Plate> plates = new ArrayList<>(session.plates.size());
        for (ClientboundTerminalBallisticsPacket.Plate plate : session.plates) {
            plates.add(new ClientboundTerminalBallisticsPacket.Plate(frame.toItemLocal(plate.pos()),
                plate.outcome(), plate.damage(), plate.toughness(), plate.broken(), plate.blockStateId()));
        }

        ClientboundTerminalBallisticsPacket.Spall spall = null;
        if (session.spall != null) {
            List<ClientboundTerminalBallisticsPacket.SpallBlock> blocks = new ArrayList<>(session.spallBlocks.size());
            for (ClientboundTerminalBallisticsPacket.SpallBlock block : session.spallBlocks) {
                blocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(
                    frame.toItemLocal(block.pos()), block.broken(), block.armorLike()));
            }
            ClientboundTerminalBallisticsPacket.Spall converted = session.spall;
            spall = new ClientboundTerminalBallisticsPacket.Spall(frame.toItemLocal(converted.origin()),
                frame.toLocalDir(converted.dir()), converted.coneCos(), converted.range(),
                converted.fragments(), blocks, converted.rayStops());
        }

        List<ClientboundTerminalBallisticsPacket.EntityHit> entities = new ArrayList<>(session.entities.size());
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : session.entities) {
            entities.add(new ClientboundTerminalBallisticsPacket.EntityHit(
                hit.entityId(), frame.toLocal(hit.pos()), hit.damage(), hit.killed()));
        }

        String summary = buildSummary(session);
        CBCTerminalBallistics.LOGGER.info(
            "[CTB-HITCAM] dispatch shell={} tick={} anchor={} pathPts={} plates={} spall={} entities={} summary=\"{}\"",
            projectile.getId(), projectile.tickCount,
            frame.anchor == null ? "world" : frame.anchor.kind(),
            path.size(), plates.size(), spall == null ? 0 : spall.fragments(), entities.size(), summary);
        send(session.audience, new ClientboundTerminalBallisticsPacket(
            projectile.getId(), EntityType.getKey(projectile.getType()).toString(),
            summary, frame.anchor, path, plates, spall, entities));
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
        int killed = 0;
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : session.entities) {
            if (hit.killed()) killed++;
        }
        StringBuilder sb = new StringBuilder(classify(session));
        sb.append(" \u00b7 ").append(session.plates.size()).append(session.plates.size() == 1 ? " plate" : " plates");
        if (!session.entities.isEmpty()) {
            sb.append(" \u00b7 ").append(session.entities.size()).append(" crew");
            if (killed > 0) sb.append(" (").append(killed).append(" KIA)");
        }
        if (session.spall != null) {
            sb.append(" \u00b7 ").append(session.spall.fragments()).append(" spall");
        }
        return sb.toString();
    }

    /**
     * Three-way engagement classification: HIT when anything behind the armour
     * was damaged (spall into internals, the shell body punching through
     * non-armour structure, or any entity hit); RICOCHET when the shell bounced
     * off armour without such damage; NO PENETRATION when only armour-classified
     * blocks were hit, even if several layers were perforated.
     */
    private static String classify(Session session) {
        if (session.internalDamage || !session.entities.isEmpty()) return "HIT";
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

    private static ServerPlayer resolveAudience(ServerLevel level, Entity projectile) {
        if (projectile instanceof net.minecraft.world.entity.projectile.Projectile p
            && p.getOwner() instanceof ServerPlayer owner) {
            return owner;
        }
        // Cannon-fired shells carry no vanilla owner; fall back to the operator
        // standing closest to the muzzle (the gunner of the firing contraption).
        ServerPlayer nearest = null;
        double best = AUDIENCE_SEARCH_RADIUS * AUDIENCE_SEARCH_RADIUS;
        Vec3 pos = projectile.position();
        for (Player player : level.players()) {
            if (!(player instanceof ServerPlayer serverPlayer)) continue;
            double distSq = player.distanceToSqr(pos);
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

    private TerminalBallisticsRecorder() {}
}
