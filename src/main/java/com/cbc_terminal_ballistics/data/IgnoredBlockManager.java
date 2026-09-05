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
 * Data-driven list of blocks projectiles treat as if they do not exist. Hits on
 * ignored blocks pass straight through: no damage, no destruction, no marks, and
 * no fuze trigger against the block.
 *
 * <p>JSON files live at {@code data/<namespace>/terminal_ballistics/ignored_blocks/<name>.json}
 * and merge across datapacks, mirroring the material files:
 * <pre>
 * {
 *   "blocks": ["create:mechanical_bearing"],
 *   "tags": ["#minecraft:mineable/pickaxe"]
 * }
 * </pre>
 * Block ids are matched exactly (unregistered ids from optional mods are kept so
 * they resolve once loaded); tags match any block in the given block tag.
 */
public class IgnoredBlockManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    public static final IgnoredBlockManager INSTANCE = new IgnoredBlockManager();

    private volatile Set<ResourceLocation> byBlock = Set.of();
    private volatile Set<TagKey<Block>> byTag = Set.of();

    private IgnoredBlockManager() {
        super(GSON, "terminal_ballistics/ignored_blocks");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Set<ResourceLocation> blocks = new HashSet<>();
        Set<TagKey<Block>> tags = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            try {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                if (obj.has("block")) addBlock(blocks, obj.get("block").getAsString());
                if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
                    obj.getAsJsonArray("blocks").forEach(e -> addBlock(blocks, e.getAsString()));
                }
                if (obj.has("tag")) addTag(tags, obj.get("tag").getAsString());
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    obj.getAsJsonArray("tags").forEach(e -> addTag(tags, e.getAsString()));
                }
            } catch (Exception ex) {
                CBCTerminalBallistics.LOGGER.warn("Failed to load ignored block entry {}", entry.getKey(), ex);
            }
        }
        this.byBlock = Set.copyOf(blocks);
        this.byTag = Set.copyOf(tags);
        CBCTerminalBallistics.LOGGER.info("[CTB] Loaded {} ignored block ids and {} ignored block tags",
            blocks.size(), tags.size());
    }

    public boolean isIgnored(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (byBlock.contains(blockId)) return true;
        for (TagKey<Block> tag : byTag) {
            if (state.is(tag)) return true;
        }
        return false;
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
