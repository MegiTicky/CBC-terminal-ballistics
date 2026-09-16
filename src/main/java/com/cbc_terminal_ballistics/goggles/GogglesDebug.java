package com.cbc_terminal_ballistics.goggles;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.network.ClientboundGogglesDebugPacket;
import com.cbc_terminal_ballistics.network.TBNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side collector for the {@code /cbctb goggles debug} overlay. While
 * at least one player has the overlay toggled on, every cannon-projectile
 * spawn is tracked from two directions: {@link EntityJoinLevelEvent} catches
 * shells the moment they enter the level — including shells that are removed
 * before their first tick, the one failure path that otherwise leaves no
 * trace at all — and the audience resolution in
 * {@code TerminalBallisticsRecorder} reports the final verdict together with
 * the per-link match details {@link GoggleLinkManager} computed. One debug
 * packet goes to the toggling players per detected shell.
 */
@Mod.EventBusSubscriber(modid = CBCTerminalBallistics.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GogglesDebug {
    private static final Set<UUID> VIEWERS = new HashSet<>();
    private static final Map<Integer, Candidate> PENDING = new HashMap<>();
    /** A shell that has not resolved this long after joining never ticked. */
    private static final long PENDING_TIMEOUT_MILLIS = 10_000L;
    private static final int PENDING_CAP = 256;
    private static final int MAX_LINK_DIAGS = 16;
    private static final int SWEEP_EVERY_TICKS = 20;

    private static final class Candidate {
        final ServerLevel level;
        final Vec3 pos;
        final String className;
        final long joinedMillis;
        List<ClientboundGogglesDebugPacket.LinkDiag> links = List.of();

        Candidate(ServerLevel level, Vec3 pos, String className, long joinedMillis) {
            this.level = level;
            this.pos = pos;
            this.className = className;
            this.joinedMillis = joinedMillis;
        }
    }

    /** Per-link detail of the most recent goggleAudiences pass (server thread, sequential). */
    private static List<ClientboundGogglesDebugPacket.LinkDiag> lastLinks = List.of();
    private static int tickCounter;

    private GogglesDebug() {}

    /** {@code /cbctb goggles debug} — toggles the overlay for this player. */
    public static boolean toggle(ServerPlayer player) {
        boolean on;
        if (!VIEWERS.remove(player.getUUID())) {
            VIEWERS.add(player.getUUID());
            on = true;
        } else {
            on = false;
        }
        if (VIEWERS.isEmpty()) {
            PENDING.clear();
            lastLinks = List.of();
        }
        return on;
    }

    public static boolean hasViewers() {
        return !VIEWERS.isEmpty();
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (VIEWERS.isEmpty() || event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        String cls = entity.getClass().getName();
        if (!cls.contains("CannonProjectile") && !cls.contains("AutocannonProjectile")) return;
        sweep(System.currentTimeMillis());
        if (PENDING.size() >= PENDING_CAP) PENDING.clear();
        PENDING.put(entity.getId(), new Candidate(level, entity.position(), cls, System.currentTimeMillis()));
    }

    /** {@code goggleAudiences} stashes the per-link detail of the pass it just ran. */
    public static void stashLinks(List<ClientboundGogglesDebugPacket.LinkDiag> links) {
        lastLinks = links == null ? List.of() : links;
    }

    /** {@code resolveAudiences} reports the final verdict for this projectile. */
    public static void report(Entity projectile, String outcome) {
        if (VIEWERS.isEmpty()) return;
        if (!(projectile.level() instanceof ServerLevel level)) return;
        Candidate candidate = PENDING.remove(projectile.getId());
        Vec3 pos = candidate != null ? candidate.pos : projectile.position();
        String className = candidate != null ? candidate.className : projectile.getClass().getName();
        List<ClientboundGogglesDebugPacket.LinkDiag> links = candidate != null && !candidate.links.isEmpty()
            ? candidate.links : lastLinks;
        lastLinks = List.of();
        send(new ClientboundGogglesDebugPacket(pos.x, pos.y, pos.z, shortName(className), outcome, links), level);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (VIEWERS.isEmpty() || PENDING.isEmpty()) return;
        if (++tickCounter < SWEEP_EVERY_TICKS) return;
        tickCounter = 0;
        sweep(System.currentTimeMillis());
    }

    /** Drops candidates that never resolved, reporting them as NEVER TICKED. */
    private static void sweep(long now) {
        if (PENDING.isEmpty()) return;
        Iterator<Map.Entry<Integer, Candidate>> it = PENDING.entrySet().iterator();
        List<Candidate> expired = null;
        while (it.hasNext()) {
            Candidate candidate = it.next().getValue();
            if (now - candidate.joinedMillis <= PENDING_TIMEOUT_MILLIS) continue;
            it.remove();
            if (expired == null) expired = new ArrayList<>();
            expired.add(candidate);
        }
        if (expired == null) return;
        for (Candidate candidate : expired) {
            send(new ClientboundGogglesDebugPacket(candidate.pos.x, candidate.pos.y, candidate.pos.z,
                shortName(candidate.className), "NEVER TICKED (removed before first tick)", candidate.links),
                candidate.level);
        }
    }

    private static void send(ClientboundGogglesDebugPacket packet, ServerLevel level) {
        if (TBNetwork.CHANNEL == null) return;
        for (UUID uuid : VIEWERS) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            if (player == null || player.level() != level || player.connection == null) continue;
            TBNetwork.CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private static String shortName(String cls) {
        int dot = cls.lastIndexOf('.');
        return dot >= 0 ? cls.substring(dot + 1) : cls;
    }
}
