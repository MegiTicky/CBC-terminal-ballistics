package com.cbc_terminal_ballistics.goggles;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.VSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side link storage and audience resolution for the ballistic goggles.
 * Each goggles item carries a list of ship-anchored cannon-mount links (ship
 * id + ship-local offset, with a plain world fallback for ground mounts), so a
 * linked gun keeps feeding its views as the ship sails. A shell opens the
 * goggle views when it spawns near a linked mount; CBC projectiles carry no
 * shooter identity at all, so this explicit link is what redstone-fired
 * defenses are watched through.
 */
public final class GoggleLinkManager {
    public static final String LINKS_TAG = "GoggleLinks";
    public static final int MAX_LINKS = 16;
    /** Marker ship offset for links that are not on a VS ship. */
    private static final long NO_SHIP_OFFSET = Long.MIN_VALUE;

    /** One cannon-mount link that survives the mount's ship moving. */
    public record GoggleLink(String dimension, long shipId, long shipOffset, long fallbackPos) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Dim", dimension);
            tag.putLong("ShipId", shipId);
            if (shipOffset != NO_SHIP_OFFSET) tag.putLong("ShipOffset", shipOffset);
            tag.putLong("FallbackPos", fallbackPos);
            return tag;
        }

        static GoggleLink load(CompoundTag tag) {
            if (!tag.contains("Dim") || !tag.contains("FallbackPos")) return null;
            long shipOffset = tag.contains("ShipOffset") ? tag.getLong("ShipOffset") : NO_SHIP_OFFSET;
            return new GoggleLink(tag.getString("Dim"), tag.getLong("ShipId"), shipOffset, tag.getLong("FallbackPos"));
        }
    }

    /**
     * True when the clicked block is a CBC cannon mount (regular or fixed).
     * Kept a class-name check to honour the codebase convention of never
     * importing CBC classes (they move between CBC versions).
     */
    public static boolean isCannonMount(BlockState state) {
        return state != null && state.hasBlockEntity()
            && state.getBlock().getClass().getName().contains("CannonMountBlock");
    }

    // ---- item NBT -------------------------------------------------------------

    public static List<GoggleLink> links(ItemStack stack) {
        List<GoggleLink> out = new ArrayList<>();
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return out;
        ListTag list = stack.getTag().getList(LINKS_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            GoggleLink link = GoggleLink.load(list.getCompound(i));
            if (link != null) out.add(link);
        }
        return out;
    }

    public static int linkCount(ItemStack stack) {
        return links(stack).size();
    }

    /** Adds a link for the mount at {@code target}. "linked" | "already" | "full". */
    public static String addLink(ItemStack stack, ServerLevel level, BlockPos target) {
        GoggleLink link = linkFor(level, target);
        List<GoggleLink> existing = links(stack);
        for (GoggleLink e : existing) {
            if (e.equals(link)) return "already";
        }
        if (existing.size() >= MAX_LINKS) return "full";
        ListTag list = stack.getOrCreateTag().getList(LINKS_TAG, Tag.TAG_COMPOUND);
        list.add(link.save());
        stack.getOrCreateTag().put(LINKS_TAG, list);
        return "linked";
    }

    /** Removes the link matching the mount at {@code target}. */
    public static boolean removeLink(ItemStack stack, ServerLevel level, BlockPos target) {
        GoggleLink link = linkFor(level, target);
        ListTag list = stack.hasTag() ? stack.getTag().getList(LINKS_TAG, Tag.TAG_COMPOUND) : new ListTag();
        for (int i = 0; i < list.size(); i++) {
            GoggleLink e = GoggleLink.load(list.getCompound(i));
            if (link.equals(e)) {
                list.remove(i);
                if (list.isEmpty()) stack.getTag().remove(LINKS_TAG);
                return true;
            }
        }
        return false;
    }

    public static void clearLinks(ItemStack stack) {
        if (stack != null && stack.hasTag()) stack.getTag().remove(LINKS_TAG);
    }

    /** The link identity of the mount at {@code target}, ship-anchored when possible. */
    private static GoggleLink linkFor(ServerLevel level, BlockPos target) {
        String dimension = level.dimension().location().toString();
        Object ship = VSCompat.shipManaging(level, target);
        if (ship != null) {
            long shipId = VSCompat.shipId(ship);
            Vec3 local = VSCompat.toShipLocal(ship, Vec3.atCenterOf(target));
            if (shipId >= 0L && local != null) {
                return new GoggleLink(dimension, shipId, BlockPos.containing(local).asLong(), target.immutable().asLong());
            }
        }
        return new GoggleLink(dimension, -1L, NO_SHIP_OFFSET, target.immutable().asLong());
    }

    // ---- audience resolution ----------------------------------------------------

    /**
     * Players whose goggles match this shell: every holder of ballistic
     * goggles with a link resolving near {@code spawn}, then nearest spotter
     * goggle holders (the opt-in "nearby shells" behaviour).
     */
    public static List<ServerPlayer> goggleAudiences(ServerLevel level, Vec3 spawn) {
        List<ServerPlayer> linked = new ArrayList<>();
        List<ServerPlayer> spotters = new ArrayList<>();
        double spotterRadiusSq = square(TBConfig.HITCAM_SPOTTER_RADIUS.get());
        Map<Long, Object> shipCache = new HashMap<>();
        BlockPos spawnBlock = BlockPos.containing(spawn);

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) continue;
            boolean matched = false;
            boolean carriesSpotter = false;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.getItem() instanceof BallisticGogglesItem) {
                    if (anyLinkMatches(level, stack, spawn, spawnBlock, shipCache)) matched = true;
                } else if (stack.getItem() instanceof SpotterGogglesItem) {
                    carriesSpotter = true;
                }
            }
            if (matched) {
                linked.add(player);
            } else if (carriesSpotter && player.distanceToSqr(spawn) <= spotterRadiusSq) {
                spotters.add(player);
            }
        }

        // Spotters nearest first, so the audience cap keeps the closest watchers.
        spotters.sort((a, b) -> Double.compare(a.distanceToSqr(spawn), b.distanceToSqr(spawn)));
        List<ServerPlayer> out = new ArrayList<>(linked);
        out.addAll(spotters);
        return out;
    }

    /** True when any link on {@code stack} resolves within the match radius of the shell spawn. */
    public static boolean anyLinkMatches(ServerLevel level, ItemStack stack, Vec3 spawn, BlockPos spawnBlock,
                                         Map<Long, Object> shipCache) {
        double radiusSq = square(TBConfig.HITCAM_GOGGLE_MATCH_RADIUS.get());
        for (GoggleLink link : links(stack)) {
            BlockPos resolved = resolveLink(level, link, shipCache);
            if (resolved != null && resolved.distSqr(spawnBlock) <= radiusSq) return true;
        }
        return false;
    }

    /** Current world position of a stored link, or null when its ship is gone or the dimension differs. */
    public static BlockPos resolveLink(ServerLevel level, GoggleLink link, Map<Long, Object> shipCache) {
        if (!link.dimension().equals(level.dimension().location().toString())) return null;
        if (link.shipId() >= 0L && link.shipOffset() != NO_SHIP_OFFSET) {
            Object ship = shipCache != null ? shipCache.get(link.shipId()) : null;
            if (ship == null) {
                ship = VSCompat.shipById(level, link.shipId());
                if (ship == null) return null;
                if (shipCache != null) shipCache.put(link.shipId(), ship);
            }
            Vec3 world = VSCompat.shipLocalToWorld(ship, Vec3.atCenterOf(BlockPos.of(link.shipOffset())));
            return world == null ? null : BlockPos.containing(world);
        }
        return BlockPos.of(link.fallbackPos());
    }

    /**
     * Setup-block integration (called reflectively by VS Analog Warfare's
     * vehicle setup action): links every goggles stack in the acting player's
     * inventory to all given cannon mounts. A stored link whose ship anchor
     * matches an existing entry is re-pointed (rebuilt vehicle, schematic
     * re-paste) instead of duplicated. Returns the number of mounts linked.
     */
    public static int setupRelink(ServerPlayer player, List<BlockPos> mountPositions) {
        if (player == null || mountPositions == null || mountPositions.isEmpty()) return 0;
        ServerLevel level = player.serverLevel();
        int changed = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!(stack.getItem() instanceof BallisticGogglesItem)) continue;
            int before = linkCount(stack);
            for (BlockPos mount : mountPositions) {
                GoggleLink link = linkFor(level, mount);
                if (rebaseOrAdd(stack, link)) changed++;
            }
            int delta = linkCount(stack) - before;
            if (delta != 0) {
                player.displayClientMessage(Component.literal(
                    "Ballistic goggles linked to " + linkCount(stack) + " cannon mount(s)."), false);
            }
        }
        return changed;
    }

    /** Re-points a matching ship-anchored link, else appends. Returns true when the stack changed. */
    private static boolean rebaseOrAdd(ItemStack stack, GoggleLink link) {
        List<GoggleLink> existing = links(stack);
        for (int i = 0; i < existing.size(); i++) {
            GoggleLink e = existing.get(i);
            boolean sameAnchor = e.shipId() == link.shipId() && e.shipOffset() == link.shipOffset()
                && e.dimension().equals(link.dimension());
            if (sameAnchor) {
                if (e.fallbackPos() == link.fallbackPos()) return false;
                replaceAt(stack, i, link);
                return true;
            }
        }
        if (existing.size() >= MAX_LINKS) return false;
        ListTag list = stack.getOrCreateTag().getList(LINKS_TAG, Tag.TAG_COMPOUND);
        list.add(link.save());
        stack.getOrCreateTag().put(LINKS_TAG, list);
        return true;
    }

    private static void replaceAt(ItemStack stack, int index, GoggleLink link) {
        ListTag list = stack.getOrCreateTag().getList(LINKS_TAG, Tag.TAG_COMPOUND);
        list.set(index, link.save());
        stack.getOrCreateTag().put(LINKS_TAG, list);
    }

    private static double square(int value) {
        return (double) value * value;
    }

    private GoggleLinkManager() {}
}
