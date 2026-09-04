package com.cbc_terminal_ballistics.state;

import com.cbc_terminal_ballistics.config.TBConfig;
import com.cbc_terminal_ballistics.util.ContraptionCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EmbeddedShellSavedData extends SavedData {
    private static final String NAME = "cbc_terminal_ballistics_embedded_shells";
    private final Map<Long, Entry> entries = new HashMap<>();

    public static EmbeddedShellSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EmbeddedShellSavedData::load, EmbeddedShellSavedData::new, NAME);
    }

    public Entry getEntry(ServerLevel level, BlockPos pos) {
        return getEntryByKey(level, pos.asLong());
    }

    @Nullable
    public Entry getEntryByKey(ServerLevel level, long key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        BlockState current = currentBlockState(level, entry);
        // Unloaded contraption entity: keep the entry (TTL still expires it).
        if (current != null && !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(current))) {
            entries.remove(key);
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
            entry = new Entry(key, fingerprint, new ArrayList<>(), null, null);
            entries.put(key, entry);
            setDirty();
        }
        return entry;
    }

    /** Create/reuse an entry anchored to a Create contraption block. */
    public Entry entryForContraption(ServerLevel level, UUID contraptionUuid, BlockPos localPos, BlockState state) {
        long key = ArmorIntegritySavedData.contraptionKey(contraptionUuid, localPos);
        String fingerprint = ArmorIntegritySavedData.fingerprint(state);
        Entry entry = entries.get(key);
        if (entry == null || !entry.fingerprint.equals(fingerprint)) {
            entry = new Entry(key, fingerprint, new ArrayList<>(), contraptionUuid, localPos.immutable());
            entries.put(key, entry);
            setDirty();
        }
        return entry;
    }

    public void add(ServerLevel level, BlockPos pos, BlockState state, EmbeddedShell shell) {
        addShell(level, entryFor(level, pos, state), shell);
    }

    /** Add a shell to a contraption-anchored entry and return it for syncing. */
    public Entry addContraption(ServerLevel level, UUID contraptionUuid, BlockPos localPos, BlockState state, EmbeddedShell shell) {
        Entry entry = entryForContraption(level, contraptionUuid, localPos, state);
        addShell(level, entry, shell);
        return entry;
    }

    private void addShell(ServerLevel level, Entry entry, EmbeddedShell shell) {
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

    /**
     * Removes expired/invalid entries and returns the removed entries so callers can
     * clear the right client-side store (world position OR contraption identity).
     */
    public List<Entry> cleanup(ServerLevel level) {
        long now = level.getGameTime();
        int lifetime = TBConfig.EMBEDDED_SHELL_LIFETIME_TICKS.get();
        List<Entry> changed = new ArrayList<>();
        Iterator<Map.Entry<Long, Entry>> iterator = entries.entrySet().iterator();
        boolean dirty = false;
        while (iterator.hasNext()) {
            Map.Entry<Long, Entry> mapEntry = iterator.next();
            Entry entry = mapEntry.getValue();
            if (invalidEntry(level, entry)) {
                iterator.remove();
                changed.add(entry);
                dirty = true;
                continue;
            }
            if (lifetime > 0 && entry.shells.removeIf(shell -> now - shell.gameTime() >= lifetime)) {
                changed.add(entry);
                dirty = true;
            }
            if (entry.shells.isEmpty()) {
                iterator.remove();
                changed.add(entry);
                dirty = true;
            }
        }
        if (dirty) setDirty();
        return changed;
    }

    private static boolean invalidEntry(ServerLevel level, Entry entry) {
        if (entry.contraptionUuid != null && entry.localPos != null) {
            Entity contraption = level.getEntity(entry.contraptionUuid);
            if (contraption == null) return false; // unloaded: keep until TTL expires
            BlockState state = ContraptionCompat.blockStateForLocal(contraption, entry.localPos);
            return state == null || !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(state));
        }
        BlockPos pos = BlockPos.of(entry.key);
        return level.isEmptyBlock(pos) && ContraptionCompat.blockStateAt(level, pos) == null
            || !entry.fingerprint.equals(ArmorIntegritySavedData.fingerprint(currentBlockState(level, entry)));
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("Pos", entry.key);
            entryTag.putString("Fp", entry.fingerprint);
            if (entry.contraptionUuid != null && entry.localPos != null) {
                entryTag.putUUID("Contraption", entry.contraptionUuid);
                entryTag.putLong("LocalPos", entry.localPos.asLong());
            }
            ListTag shells = new ListTag();
            entry.shells.forEach(shell -> shells.add(shell.save()));
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
            UUID contraptionUuid = entryTag.hasUUID("Contraption") ? entryTag.getUUID("Contraption") : null;
            BlockPos localPos = entryTag.contains("LocalPos") ? BlockPos.of(entryTag.getLong("LocalPos")) : null;
            data.entries.put(entryTag.getLong("Pos"), new Entry(entryTag.getLong("Pos"), entryTag.getString("Fp"), shells, contraptionUuid, localPos));
        }
        return data;
    }

    @Nullable
    private static BlockState currentBlockState(ServerLevel level, Entry entry) {
        if (entry.contraptionUuid != null && entry.localPos != null) {
            Entity contraption = level.getEntity(entry.contraptionUuid);
            if (contraption == null) return null;
            return ContraptionCompat.blockStateForLocal(contraption, entry.localPos);
        }
        BlockPos pos = BlockPos.of(entry.key);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            BlockState contraptionState = ContraptionCompat.blockStateAt(level, pos);
            if (contraptionState != null) return contraptionState;
        }
        return state;
    }

    public static final class Entry {
        public final long key;
        public final String fingerprint;
        public final List<EmbeddedShell> shells;
        /** Non-null when this entry is anchored to a Create contraption block. */
        @Nullable public final UUID contraptionUuid;
        @Nullable public final BlockPos localPos;

        private Entry(long key, String fingerprint, List<EmbeddedShell> shells,
                      @Nullable UUID contraptionUuid, @Nullable BlockPos localPos) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.shells = shells;
            this.contraptionUuid = contraptionUuid;
            this.localPos = localPos;
        }

        /** World storage key decoded as a position (meaningless for contraption entries). */
        public BlockPos keyPos() {
            return BlockPos.of(key);
        }
    }
}
