package com.cbc_terminal_ballistics.hitcam;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.network.ClientboundTerminalBallisticsPacket;
import com.cbc_terminal_ballistics.network.ClientboundTrackProjectilePacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
     * armour-like or contraption blocks are reported; terrain hits are ignored.
     */
    public static void onPlateImpact(Entity projectile, Vec3 worldPos, String outcome, double damage,
                                     double toughness, boolean broken, @Nullable Entity contraptionEntity) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null) return;
        if (session.firstEventGameTime < 0L) session.firstEventGameTime = projectile.level().getGameTime();
        if (session.plates.size() < MAX_PLATES) {
            session.plates.add(new ClientboundTerminalBallisticsPacket.Plate(worldPos, outcome, damage, toughness, broken));
        }
        if (contraptionEntity != null && session.anchorUuid == null) session.anchorUuid = contraptionEntity.getUUID();
        if (!"PENETRATE".equals(outcome)) session.hasTerminalOutcome = true;
    }

    public static void onSpall(Entity projectile, Vec3 origin, Vec3 dir, double coneCos, double range, int fragments) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null || fragments <= 0) return;
        session.spall = new ClientboundTerminalBallisticsPacket.Spall(origin, dir, coneCos, range, fragments, session.spallBlocks);
    }

    public static void onSpallBlock(Entity projectile, Vec3 worldPos, boolean broken) {
        if (!TBConfig.HITCAM_TERMINAL_VIEW.get()) return;
        Session session = SESSIONS.get(projectile);
        if (session == null || session.spallBlocks.size() >= MAX_SPALL_BLOCKS) return;
        session.spallBlocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(worldPos, broken));
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
     * died at the armour (STOP/BOUNCE — CBC removes it) or when the session
     * went stale; pure pass-through shells keep accumulating so the diagram
     * shows every plate in one shot.
     */
    public static void onClipAndDamageTail(Entity projectile) {
        Session session = SESSIONS.get(projectile);
        if (session == null) return;
        boolean stale = session.firstEventGameTime >= 0L
            && projectile.level().getGameTime() - session.firstEventGameTime >= STALE_FLUSH_TICKS;
        if (session.hasTerminalOutcome || stale) {
            finalizeAndSend(projectile, session);
        }
    }

    /** Projectile left the level: final flush for pass-through shells. */
    public static void onEntityRemoved(Entity projectile) {
        Session session = SESSIONS.remove(projectile);
        if (session != null) {
            finalizeAndSend(projectile, session);
        }
    }

    // ---- finalize ----------------------------------------------------------------

    private static void finalizeAndSend(Entity projectile, Session session) {
        SESSIONS.remove(projectile);
        if (session.plates.isEmpty() || session.audience == null) return;
        if (!(projectile.level() instanceof ServerLevel level)) return;

        boolean anchored = session.anchorUuid != null;
        Entity anchor = null;
        if (anchored) {
            anchor = findEntityByUuid(level, session.anchorUuid);
            if (anchor == null) anchored = false;
        }

        List<HitcamSample> rawPath = HitcamRecorder.samplesOf(projectile);
        List<HitcamSample> path = new ArrayList<>(rawPath.size());
        for (HitcamSample sample : rawPath) {
            path.add(anchoredSample(anchor, sample));
        }

        List<ClientboundTerminalBallisticsPacket.Plate> plates = new ArrayList<>(session.plates.size());
        for (ClientboundTerminalBallisticsPacket.Plate plate : session.plates) {
            plates.add(new ClientboundTerminalBallisticsPacket.Plate(toAnchor(anchor, plate.pos()),
                plate.outcome(), plate.damage(), plate.toughness(), plate.broken()));
        }

        ClientboundTerminalBallisticsPacket.Spall spall = null;
        if (session.spall != null) {
            List<ClientboundTerminalBallisticsPacket.SpallBlock> blocks = new ArrayList<>(session.spallBlocks.size());
            for (ClientboundTerminalBallisticsPacket.SpallBlock block : session.spallBlocks) {
                blocks.add(new ClientboundTerminalBallisticsPacket.SpallBlock(toAnchor(anchor, block.pos()), block.broken()));
            }
            ClientboundTerminalBallisticsPacket.Spall converted = session.spall;
            spall = new ClientboundTerminalBallisticsPacket.Spall(toAnchor(anchor, converted.origin()),
                toAnchorDirection(anchor, converted.dir()), converted.coneCos(), converted.range(),
                converted.fragments(), blocks);
        }

        List<ClientboundTerminalBallisticsPacket.EntityHit> entities = new ArrayList<>(session.entities.size());
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : session.entities) {
            entities.add(new ClientboundTerminalBallisticsPacket.EntityHit(
                hit.entityId(), toAnchor(anchor, hit.pos()), hit.damage(), hit.killed()));
        }

        String summary = buildSummary(session);
        send(session.audience, new ClientboundTerminalBallisticsPacket(
            projectile.getId(), summary, anchored && anchor != null ? anchor.getUUID().toString() : null,
            path, plates, spall, entities));
    }

    private static String buildSummary(Session session) {
        String last = session.plates.get(session.plates.size() - 1).outcome();
        int killed = 0;
        for (ClientboundTerminalBallisticsPacket.EntityHit hit : session.entities) {
            if (hit.killed()) killed++;
        }
        StringBuilder sb = new StringBuilder(last);
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

    private static HitcamSample anchoredSample(@Nullable Entity anchor, HitcamSample sample) {
        if (anchor == null) return sample;
        Vec3 local = toAnchor(anchor, new Vec3(sample.x(), sample.y(), sample.z()));
        Vec3 localDir = toAnchorDirection(anchor, new Vec3(sample.ox(), sample.oy(), sample.oz()));
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
