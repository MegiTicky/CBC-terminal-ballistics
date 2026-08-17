package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class EmbeddedShellSavedData extends SavedData {
    private static final String NAME = "cbc_terminal_ballistics_embedded_shells";
    private final Map<BlockPos, Entry> entries = new HashMap<>();

    public static EmbeddedShellSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(EmbeddedShellSavedData::new, EmbeddedShellSavedData::load), NAME);
    }

    public Entry getEntry(ServerLevel level, BlockPos pos) {
        Entry entry = entries.get(pos);
        if (entry == null) return null;
        if (!entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(level.getBlockState(pos)))
            || !Objects.equals(entry.subLevelId, SableCompat.subLevelId(level, pos))) {
            entries.remove(pos);
            setDirty();
            return null;
        }
        return entry;
    }

    public Entry entryFor(ServerLevel level, BlockPos pos, BlockState state) {
        UUID subLevelId = SableCompat.subLevelId(level, pos);
        Entry entry = entries.get(pos);
        if (entry == null || !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(state))
            || !Objects.equals(entry.subLevelId, subLevelId)) {
            entry = new Entry(ArmorIntegritySavedData.fingerprint(state), subLevelId, new ArrayList<>());
            entries.put(pos.immutable(), entry);
            setDirty();
        }
        return entry;
    }

    public void add(ServerLevel level, BlockPos pos, BlockState state, EmbeddedShell shell) {
        Entry entry = entryFor(level, pos, state);
        entry.shells.add(shell);
        while (entry.shells.size() > TBConfig.EMBEDDED_SHELLS_PER_BLOCK.get()) entry.shells.remove(0);
        setDirty();
    }

    public void clear(BlockPos pos) { if (entries.remove(pos) != null) setDirty(); }
    public Map<BlockPos, Entry> entriesView() { return java.util.Collections.unmodifiableMap(entries); }

    public List<BlockPos> cleanup(ServerLevel level) {
        long now = level.getGameTime();
        int lifetime = TBConfig.EMBEDDED_SHELL_LIFETIME_TICKS.get();
        List<BlockPos> changed = new ArrayList<>();
        boolean dirty = false;
        Iterator<Map.Entry<BlockPos, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Entry> mapEntry = iterator.next();
            BlockPos pos = mapEntry.getKey(); Entry entry = mapEntry.getValue();
            if (level.isEmptyBlock(pos) || !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(level.getBlockState(pos)))
                || !Objects.equals(entry.subLevelId, SableCompat.subLevelId(level, pos))) {
                iterator.remove(); changed.add(pos); dirty = true; continue;
            }
            if (lifetime > 0 && entry.shells.removeIf(shell -> now - shell.gameTime() >= lifetime)) { changed.add(pos); dirty = true; }
            if (entry.shells.isEmpty()) { iterator.remove(); changed.add(pos); dirty = true; }
        }
        if (dirty) setDirty();
        return changed;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, Entry> mapEntry : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag(); BlockPos pos = mapEntry.getKey(); Entry entry = mapEntry.getValue();
            entryTag.putInt("X", pos.getX()); entryTag.putInt("Y", pos.getY()); entryTag.putInt("Z", pos.getZ());
            entryTag.putString("Fp", entry.fingerprint); if (entry.subLevelId != null) entryTag.putUUID("SubLevelId", entry.subLevelId);
            ListTag shells = new ListTag(); entry.shells.forEach(shell -> shells.add(shell.save(registries))); entryTag.put("Shells", shells); list.add(entryTag);
        }
        tag.put("Entries", list); return tag;
    }

    public static EmbeddedShellSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        EmbeddedShellSavedData data = new EmbeddedShellSavedData();
        for (Tag raw : tag.getList("Entries", Tag.TAG_COMPOUND)) {
            CompoundTag entryTag = (CompoundTag) raw; BlockPos pos = new BlockPos(entryTag.getInt("X"), entryTag.getInt("Y"), entryTag.getInt("Z"));
            UUID subLevelId = entryTag.hasUUID("SubLevelId") ? entryTag.getUUID("SubLevelId") : null; List<EmbeddedShell> shells = new ArrayList<>();
            for (Tag shellTag : entryTag.getList("Shells", Tag.TAG_COMPOUND)) shells.add(EmbeddedShell.load((CompoundTag) shellTag, registries));
            data.entries.put(pos, new Entry(entryTag.getString("Fp"), subLevelId, shells));
        }
        return data;
    }

    public static final class Entry {
        public final String fingerprint; public final UUID subLevelId; public final List<EmbeddedShell> shells;
        private Entry(String fingerprint, UUID subLevelId, List<EmbeddedShell> shells) { this.fingerprint = fingerprint; this.subLevelId = subLevelId; this.shells = shells; }
    }
}
