package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Data-driven list of "mechanical component" blocks whose destruction counts as
 * a critical hit in the terminal-view damage evaluation (fluid tanks, cannon
 * mounts, launchers, ammo racks, ...). Only actual breakage triggers a
 * critical; integrity damage alone never does.
 *
 * <p>JSON files live at {@code data/<namespace>/terminal_ballistics/critical_blocks/<name>.json}
 * and merge across datapacks, mirroring the ignored-blocks list:
 * <pre>
 * {
 *   "mods":   ["create", "create_connected"],
 *   "blocks": ["createbigcannons:cannon_mount"],
 *   "tags":   ["#cbctb:critical_blocks"]
 * }
 * </pre>
 * {@code mods} entries match every block of a mod id (namespace wildcard) so
 * optional mods can be listed even when they are not installed — an uninstalled
 * mod simply never matches. {@code blocks} ids are matched exactly and are kept
 * even when unregistered so they resolve once the mod is loaded.
 */
public class CriticalBlockManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    public static final CriticalBlockManager INSTANCE = new CriticalBlockManager();

    private volatile Set<String> byMod = Set.of();
    private volatile Set<ResourceLocation> byBlock = Set.of();
    private volatile Set<TagKey<Block>> byTag = Set.of();

    private CriticalBlockManager() {
        super(GSON, "terminal_ballistics/critical_blocks");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Set<String> mods = new HashSet<>();
        Set<ResourceLocation> blocks = new HashSet<>();
        Set<TagKey<Block>> tags = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            try {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                if (obj.has("mod") && obj.get("mod").isJsonPrimitive()) addMod(mods, obj.get("mod").getAsString());
                if (obj.has("mods") && obj.get("mods").isJsonArray()) {
                    obj.getAsJsonArray("mods").forEach(e -> addMod(mods, e.getAsString()));
                }
                if (obj.has("block") && obj.get("block").isJsonPrimitive()) addBlock(blocks, obj.get("block").getAsString());
                if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
                    obj.getAsJsonArray("blocks").forEach(e -> addBlock(blocks, e.getAsString()));
                }
                if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) addTag(tags, obj.get("tag").getAsString());
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    obj.getAsJsonArray("tags").forEach(e -> addTag(tags, e.getAsString()));
                }
            } catch (Exception ex) {
                CBCTerminalBallistics.LOGGER.warn("Failed to load critical block entry {}", entry.getKey(), ex);
            }
        }
        this.byMod = Set.copyOf(mods);
        this.byBlock = Set.copyOf(blocks);
        this.byTag = Set.copyOf(tags);
        CBCTerminalBallistics.LOGGER.info("[CTB] Loaded {} critical mod wildcards, {} critical block ids and {} critical block tags",
            mods.size(), blocks.size(), tags.size());
    }

    public boolean isCritical(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId == null) return false;
        if (byMod.contains(blockId.getNamespace())) return true;
        if (byBlock.contains(blockId)) return true;
        for (TagKey<Block> tag : byTag) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    private static void addMod(Set<String> set, String raw) {
        String s = raw.toLowerCase(java.util.Locale.ROOT).trim();
        if (!s.isEmpty()) set.add(s);
    }

    private static void addBlock(Set<ResourceLocation> set, String raw) {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id != null) set.add(id);
    }

    private static void addTag(Set<TagKey<Block>> set, String raw) {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id != null) set.add(TagKey.create(Registries.BLOCK, id));
    }
}
