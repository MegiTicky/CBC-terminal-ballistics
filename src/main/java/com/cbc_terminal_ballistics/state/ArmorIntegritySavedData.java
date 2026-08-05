package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ArmorIntegritySavedData extends SavedData {
    private static final String NAME = "cbc_terminal_ballistics_integrity";
    private final Map<BlockPos, Entry> entries = new HashMap<>();

    public static ArmorIntegritySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ArmorIntegritySavedData::new, ArmorIntegritySavedData::load), NAME);
    }

    public static void clearIfServer(LevelAccessor accessor, BlockPos pos) {
        if (accessor instanceof ServerLevel level) get(level).clear(pos);
    }

    public Entry entryFor(ServerLevel level, BlockPos pos, BlockState state) {
        String fp = fingerprint(state);
        UUID subLevelId = SableCompat.subLevelId(level, pos);
        Entry e = entries.get(pos);
        if (e == null || !e.fingerprint.equals(fp) || !Objects.equals(e.subLevelId, subLevelId)) {
            e = new Entry(fp, subLevelId, 0, level.getGameTime(), new ArrayList<>());
            entries.put(pos.immutable(), e);
            setDirty();
        }
        return e;
    }

    public Entry getEntry(ServerLevel level, BlockPos pos) {
        Entry e = entries.get(pos);
        if (e == null) return null;
        if (!e.fingerprint.equals(fingerprint(level.getBlockState(pos)))
                || !Objects.equals(e.subLevelId, SableCompat.subLevelId(level, pos))) {
            entries.remove(pos);
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
        Entry e = entryFor(level, pos, state);
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
        if (a.kind() != b.kind() || a.caliber() != b.caliber() || a.surface() != b.surface() || a.face() != b.face()) return false;
        if (Math.abs(a.gameTime() - b.gameTime()) > 2L) return false;
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= 0.05D * 0.05D;
    }

    public void clear(BlockPos pos) {
        if (entries.remove(pos) != null) setDirty();
    }

    public int clearAll() {
        int count = entries.size();
        if (count > 0) {
            entries.clear();
            setDirty();
        }
        return count;
    }

    public Map<BlockPos, Entry> entriesView() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    public List<BlockPos> cleanup(ServerLevel level) {
        long now = level.getGameTime();
        int ttl = TBConfig.OVERLAY_LIFETIME_TICKS.get();
        boolean dirty = false;
        List<BlockPos> removed = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> mapEntry = it.next();
            BlockPos pos = mapEntry.getKey();
            Entry e = mapEntry.getValue();
            if (level.isEmptyBlock(pos)
                    || !e.fingerprint.equals(fingerprint(level.getBlockState(pos)))
                    || !Objects.equals(e.subLevelId, SableCompat.subLevelId(level, pos))
                    || now - e.lastTouched > ttl * 4L) {
                it.remove();
                removed.add(pos);
                dirty = true;
                continue;
            }
            if (e.marks.removeIf(m -> now - m.gameTime() > ttl)) dirty = true;
            if (e.damage <= 0.01 && e.marks.isEmpty()) { it.remove(); removed.add(pos); dirty = true; }
        }
        if (dirty) setDirty();
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Entry> mapEntry : entries.entrySet()) {
            CompoundTag eTag = new CompoundTag();
            BlockPos pos = mapEntry.getKey();
            eTag.putInt("X", pos.getX());
            eTag.putInt("Y", pos.getY());
            eTag.putInt("Z", pos.getZ());
            Entry e = mapEntry.getValue();
            eTag.putString("Fp", e.fingerprint);
            if (e.subLevelId != null) eTag.putString("SubLevelId", e.subLevelId.toString());
            eTag.putDouble("Damage", e.damage);
            eTag.putLong("Touched", e.lastTouched);
            ListTag marks = new ListTag();
            e.marks.forEach(m -> marks.add(m.save()));
            eTag.put("Marks", marks);
            list.add(eTag);
        }
        tag.put("Entries", list);
        return tag;
    }

    public static ArmorIntegritySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ArmorIntegritySavedData data = new ArmorIntegritySavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag t : list) {
            CompoundTag eTag = (CompoundTag) t;
            List<ImpactMark> marks = new ArrayList<>();
            ListTag markTags = eTag.getList("Marks", Tag.TAG_COMPOUND);
            for (Tag mt : markTags) marks.add(ImpactMark.load((CompoundTag) mt));
            BlockPos pos = new BlockPos(eTag.getInt("X"), eTag.getInt("Y"), eTag.getInt("Z"));
            UUID subLevelId = readUuid(eTag.getString("SubLevelId"));
            data.entries.put(pos, new Entry(eTag.getString("Fp"), subLevelId,
                    eTag.getDouble("Damage"), eTag.getLong("Touched"), marks));
        }
        return data;
    }

    private static UUID readUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String fingerprint(BlockState state) {
        return state.toString();
    }

    public static class Entry {
        public final String fingerprint;
        public final UUID subLevelId;
        public double damage;
        public long lastTouched;
        public final List<ImpactMark> marks;

        Entry(String fingerprint, UUID subLevelId, double damage, long lastTouched, List<ImpactMark> marks) {
            this.fingerprint = fingerprint;
            this.subLevelId = subLevelId;
            this.damage = damage;
            this.lastTouched = lastTouched;
            this.marks = marks;
        }
    }
}
