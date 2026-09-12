package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ArmorIntegritySavedData extends SavedData {
    private static final String NAME = "cbc_terminal_ballistics_integrity";
    private final Map<Long, Entry> entries = new HashMap<>();

    public static ArmorIntegritySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ArmorIntegritySavedData::load, ArmorIntegritySavedData::new, NAME);
    }

    public static void clearIfServer(LevelAccessor accessor, BlockPos pos) {
        if (accessor instanceof ServerLevel level) get(level).clear(pos);
    }

    /**
     * Stable storage key for a contraption-anchored entry. Derived from the owning
     * entity UUID and the contraption-local block position, so it survives the
     * contraption moving or rotating. (Theoretical cross-ship hash collisions only
     * merge cosmetic decals; acceptable.)
     */
    public static long contraptionKey(UUID uuid, BlockPos localPos) {
        long salt = uuid.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ uuid.getLeastSignificantBits();
        return localPos.asLong() ^ Long.hashCode(salt);
    }

    public Entry entryFor(ServerLevel level, BlockPos pos, BlockState state) {
        long key = pos.asLong();
        String fp = fingerprint(state);
        Entry e = entries.get(key);
        if (e == null || !e.fingerprint.equals(fp)) {
            e = new Entry(key, fp, 0, level.getGameTime(), new ArrayList<>(), null, null);
            entries.put(key, e);
            setDirty();
        }
        return e;
    }

    /** Create/reuse an entry anchored to a Create contraption block. */
    public Entry entryForContraption(ServerLevel level, UUID contraptionUuid, BlockPos localPos, BlockState state) {
        long key = contraptionKey(contraptionUuid, localPos);
        String fp = fingerprint(state);
        Entry e = entries.get(key);
        if (e == null || !e.fingerprint.equals(fp)) {
            e = new Entry(key, fp, 0, level.getGameTime(), new ArrayList<>(), contraptionUuid, localPos.immutable());
            entries.put(key, e);
            setDirty();
        }
        e.lastTouched = level.getGameTime();
        return e;
    }

    public Entry getEntry(ServerLevel level, BlockPos pos) {
        return getEntryByKey(level, pos.asLong());
    }

    @Nullable
    public Entry getEntryByKey(ServerLevel level, long key) {
        Entry e = entries.get(key);
        if (e == null) return null;
        BlockState current = currentBlockState(level, e);
        // A contraption entry whose entity is currently unloaded cannot be resolved;
        // keep it (the touched-TTL still expires it) instead of destroying data.
        if (current != null && !e.fingerprint.equals(fingerprint(current))) {
            entries.remove(key);
            setDirty();
            return null;
        }
        return e;
    }

    public void addDamage(ServerLevel level, BlockPos pos, BlockState state, double amount) {
        Entry e = entryFor(level, pos, state);
        e.damage += amount;
        e.lastTouched = level.getGameTime();
        setDirty();
    }

    public double damage(ServerLevel level, BlockPos pos, BlockState state) {
        return entryFor(level, pos, state).damage;
    }

    public void addMark(ServerLevel level, BlockPos pos, BlockState state, ImpactMark mark) {
        addMarkTo(level, entryFor(level, pos, state), mark);
    }

    /** Add a mark to a contraption-anchored entry and return it for syncing. */
    public Entry addContraptionMark(ServerLevel level, UUID contraptionUuid, BlockPos localPos, BlockState state, ImpactMark mark) {
        Entry e = entryForContraption(level, contraptionUuid, localPos, state);
        addMarkTo(level, e, mark);
        return e;
    }

    private void addMarkTo(ServerLevel level, Entry e, ImpactMark mark) {
        for (int i = 0; i < e.marks.size(); i++) {
            ImpactMark existing = e.marks.get(i);
            if (isNearDuplicate(existing, mark)) {
                e.marks.set(i, mark);
                e.lastTouched = level.getGameTime();
                setDirty();
                return;
            }
        }
        e.marks.add(mark);
        int max = TBConfig.OVERLAY_MARKS_PER_BLOCK.get();
        while (e.marks.size() > max) e.marks.remove(0);
        e.lastTouched = level.getGameTime();
        setDirty();
    }

    private static boolean isNearDuplicate(ImpactMark a, ImpactMark b) {
        if (a.kind() != b.kind() || a.caliber() != b.caliber() || a.face() != b.face()) return false;
        if (Math.abs(a.gameTime() - b.gameTime()) > 2L) return false;
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= 0.05D * 0.05D;
    }

    public void clear(BlockPos pos) {
        if (entries.remove(pos.asLong()) != null) setDirty();
    }

    public int clearAll() {
        int count = entries.size();
        if (count > 0) {
            entries.clear();
            setDirty();
        }
        return count;
    }

    public Map<Long, Entry> entriesView() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    /**
     * Removes expired/invalid entries and returns the removed entries so callers can
     * clear the right client-side store (world position OR contraption identity).
     */
    public List<Entry> cleanup(ServerLevel level) {
        long now = level.getGameTime();
        int ttl = TBConfig.OVERLAY_LIFETIME_TICKS.get();
        boolean dirty = false;
        List<Entry> removed = new ArrayList<>();
        Iterator<Map.Entry<Long, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Entry> mapEntry = it.next();
            Entry e = mapEntry.getValue();
            if (invalidEntry(level, e) || now - e.lastTouched > ttl * 4L) {
                it.remove();
                removed.add(e);
                dirty = true;
                continue;
            }
            if (e.marks.removeIf(m -> now - m.gameTime() > ttl)) dirty = true;
            if (e.damage <= 0.01 && e.marks.isEmpty()) { it.remove(); removed.add(e); dirty = true; }
        }
        if (dirty) setDirty();
        return removed;
    }

    private static boolean invalidEntry(ServerLevel level, Entry e) {
        if (e.contraptionUuid != null && e.localPos != null) {
            Entity contraption = level.getEntity(e.contraptionUuid);
            if (contraption == null) return false; // unloaded: keep until TTL expires
            BlockState state = ContraptionCompat.blockStateForLocal(contraption, e.localPos);
            return state == null || !e.fingerprint.equals(fingerprint(state));
        }
        BlockPos pos = BlockPos.of(e.key);
        return level.isEmptyBlock(pos) && ContraptionCompat.blockStateAt(level, pos) == null
            || !e.fingerprint.equals(fingerprint(currentBlockState(level, e)));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            CompoundTag eTag = new CompoundTag();
            eTag.putLong("Pos", e.key);
            eTag.putString("Fp", e.fingerprint);
            eTag.putDouble("Damage", e.damage);
            eTag.putLong("Touched", e.lastTouched);
            if (e.contraptionUuid != null && e.localPos != null) {
                eTag.putUUID("Contraption", e.contraptionUuid);
                eTag.putLong("LocalPos", e.localPos.asLong());
            }
            ListTag marks = new ListTag();
            e.marks.forEach(m -> marks.add(m.save()));
            eTag.put("Marks", marks);
            list.add(eTag);
        }
        tag.put("Entries", list);
        return tag;
    }

    public static ArmorIntegritySavedData load(CompoundTag tag) {
        ArmorIntegritySavedData data = new ArmorIntegritySavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag t : list) {
            CompoundTag eTag = (CompoundTag) t;
            List<ImpactMark> marks = new ArrayList<>();
            ListTag markTags = eTag.getList("Marks", Tag.TAG_COMPOUND);
            for (Tag mt : markTags) marks.add(ImpactMark.load((CompoundTag) mt));
            UUID contraptionUuid = eTag.hasUUID("Contraption") ? eTag.getUUID("Contraption") : null;
            BlockPos localPos = eTag.contains("LocalPos") ? BlockPos.of(eTag.getLong("LocalPos")) : null;
            data.entries.put(eTag.getLong("Pos"), new Entry(eTag.getLong("Pos"), eTag.getString("Fp"), eTag.getDouble("Damage"), eTag.getLong("Touched"), marks, contraptionUuid, localPos));
        }
        return data;
    }

    public static String fingerprint(BlockState state) {
        return state.toString();
    }

    /**
     * Resolve the block state an entry is anchored to. Returns null when the entry is
     * contraption-anchored and the owning entity is currently not resolvable (never
     * null for world entries — air resolves as air).
     */
    @Nullable
    private static BlockState currentBlockState(ServerLevel level, Entry e) {
        if (e.contraptionUuid != null && e.localPos != null) {
            Entity contraption = level.getEntity(e.contraptionUuid);
            if (contraption == null) return null;
            return ContraptionCompat.blockStateForLocal(contraption, e.localPos);
        }
        BlockPos pos = BlockPos.of(e.key);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            BlockState contraptionState = ContraptionCompat.blockStateAt(level, pos);
            if (contraptionState != null) return contraptionState;
        }
        return state;
    }

    public static class Entry {
        public final long key;
        public final String fingerprint;
        public double damage;
        public long lastTouched;
        public final List<ImpactMark> marks;
        /** Non-null when this entry is anchored to a Create contraption block. */
        @Nullable public final UUID contraptionUuid;
        @Nullable public final BlockPos localPos;

        Entry(long key, String fingerprint, double damage, long lastTouched, List<ImpactMark> marks,
              @Nullable UUID contraptionUuid, @Nullable BlockPos localPos) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.damage = damage;
            this.lastTouched = lastTouched;
            this.marks = marks;
            this.contraptionUuid = contraptionUuid;
            this.localPos = localPos;
        }

        /** World storage key decoded as a position (meaningless for contraption entries). */
        public BlockPos keyPos() {
            return BlockPos.of(key);
        }
    }
}
