package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.config.TBConfig;
import net.minecraft.core.BlockPos;
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

public final class EmbeddedShellSavedData extends SavedData {
    private static final String NAME = "cbc_terminal_ballistics_embedded_shells";
    private final Map<Long, Entry> entries = new HashMap<>();

    public static EmbeddedShellSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EmbeddedShellSavedData::load, EmbeddedShellSavedData::new, NAME);
    }

    public Entry getEntry(ServerLevel level, BlockPos pos) {
        Entry entry = entries.get(pos.asLong());
        if (entry == null) return null;
        if (!entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(level.getBlockState(pos)))) {
            entries.remove(pos.asLong());
            setDirty();
            return null;
        }
        return entry;
    }

    public Entry entryFor(ServerLevel level, BlockPos pos, BlockState state) {
        long key = pos.asLong();
        String fingerprint = ArmorIntegritySavedData.fingerprint(state);
        Entry entry = entries.get(key);
        if (entry == null || !entry.fingerprint.equals(fingerprint)) {
            entry = new Entry(fingerprint, new ArrayList<>());
            entries.put(key, entry);
            setDirty();
        }
        return entry;
    }

    public void add(ServerLevel level, BlockPos pos, BlockState state, EmbeddedShell shell) {
        Entry entry = entryFor(level, pos, state);
        entry.shells.add(shell);
        int max = TBConfig.EMBEDDED_SHELLS_PER_BLOCK.get();
        while (entry.shells.size() > max) entry.shells.remove(0);
        setDirty();
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

    public List<BlockPos> cleanup(ServerLevel level) {
        long now = level.getGameTime();
        int lifetime = TBConfig.EMBEDDED_SHELL_LIFETIME_TICKS.get();
        List<BlockPos> changed = new ArrayList<>();
        Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
        boolean dirty = false;
        while (iterator.hasNext()) {
            Map.Entry<Long, Entry> mapEntry = iterator.next();
            BlockPos pos = BlockPos.of(mapEntry.getKey());
            Entry entry = mapEntry.getValue();
            if (level.isEmptyBlock(pos) || !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(level.getBlockState(pos)))) {
                iterator.remove();
                changed.add(pos);
                dirty = true;
                continue;
            }
            if (lifetime > 0 && entry.shells.removeIf(shell -> now - shell.gameTime() >= lifetime)) {
                changed.add(pos);
                dirty = true;
            }
            if (entry.shells.isEmpty()) {
                iterator.remove();
                changed.add(pos);
                dirty = true;
            }
        }
        if (dirty) setDirty();
        return changed;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Entry> mapEntry : entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", mapEntry.getKey());
            entryTag.putString("Fp", mapEntry.getValue().fingerprint);
            ListTag shells = new ListTag();
            mapEntry.getValue().shells.forEach(shell -> shells.add(shell.save()));
            entryTag.put("Shells", shells);
            list.add(entryTag);
        }
        tag.put("Entries", list);
        return tag;
    }

    public static EmbeddedShellSavedData load(CompoundTag tag) {
        EmbeddedShellSavedData data = new EmbeddedShellSavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            CompoundTag entryTag = (CompoundTag) raw;
            List<EmbeddedShell> shells = new ArrayList<>();
            for (Tag shellTag : entryTag.getList("Shells", Tag.TAG_COMPOUND)) {
                shells.add(EmbeddedShell.load((CompoundTag) shellTag));
            }
            data.entries.put(entryTag.getLong("Pos"), new Entry(entryTag.getString("Fp"), shells));
        }
        return data;
    }

    public static final class Entry {
        public final String fingerprint;
        public final List<EmbeddedShell> shells;

        private Entry(String fingerprint, List<EmbeddedShell> shells) {
            this.fingerprint = fingerprint;
            this.shells = shells;
        }
    }
}
