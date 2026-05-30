package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
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

import java.util.HashMap;
import java.util.Map;

public class MaterialManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    public static final MaterialManager INSTANCE = new MaterialManager();

    private volatile Map<ResourceLocation, MaterialStats> byBlock = Map.of();
    private volatile Map<TagKey<Block>, MaterialStats> byTag = Map.of();

    private MaterialManager() {
        super(GSON, "terminal_ballistics/block_materials");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, MaterialStats> blocks = new HashMap<>();
        Map<TagKey<Block>, MaterialStats> tags = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            try {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                MaterialStats stats = MaterialStats.fromJson(obj);
                if (obj.has("block")) addBlock(blocks, obj.get("block").getAsString(), stats);
                if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
                    obj.getAsJsonArray("blocks").forEach(e -> addBlock(blocks, e.getAsString(), stats));
                }
                if (obj.has("tag")) addTag(tags, obj.get("tag").getAsString(), stats);
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    obj.getAsJsonArray("tags").forEach(e -> addTag(tags, e.getAsString(), stats));
                }
                // If no explicit selector is present, treat the file id itself as a block id for compact datapacks.
                if (!obj.has("block") && !obj.has("blocks") && !obj.has("tag") && !obj.has("tags")) {
                    addBlock(blocks, entry.getKey().toString(), stats);
                }
            } catch (Exception ex) {
                CBCTerminalBallistics.LOGGER.warn("Failed to load terminal ballistics material {}", entry.getKey(), ex);
            }
        }
        this.byBlock = Map.copyOf(blocks);
        this.byTag = Map.copyOf(tags);
        CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] Loaded {} block and {} tag terminal-ballistics material mappings", blocks.size(), tags.size());
    }

    public int blockMappingCount() { return this.byBlock.size(); }
    public int tagMappingCount() { return this.byTag.size(); }

    public MaterialStats get(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        MaterialStats direct = this.byBlock.get(blockId);
        if (direct != null) return direct;
        for (Map.Entry<TagKey<Block>, MaterialStats> entry : this.byTag.entrySet()) {
            if (state.is(entry.getKey())) return entry.getValue();
        }
        String ns = blockId.getNamespace();
        String path = blockId.getPath();
        if (ns.equals("rha") && path.contains("steel")) return heavyArmorFallback();
        if (ns.equals("s_a_b") && (path.contains("steel") || path.contains("armor"))) return path.contains("light") ? lightArmorFallback() : heavyArmorFallback();
        return MaterialStats.DEFAULT;
    }


    private static MaterialStats heavyArmorFallback() {
        return new MaterialStats(8.0, 0.08, 5.0, 0.9, Map.of(
            TBCaliber.AUTOCANNON, 0.18,
            TBCaliber.SMALL, 0.45,
            TBCaliber.SMALL_MEDIUM, 0.65,
            TBCaliber.MEDIUM, 0.85,
            TBCaliber.BIG, 1.0));
    }

    private static MaterialStats lightArmorFallback() {
        return new MaterialStats(4.0, 0.12, 3.0, 0.7, Map.of(
            TBCaliber.AUTOCANNON, 0.35,
            TBCaliber.SMALL, 0.70,
            TBCaliber.SMALL_MEDIUM, 0.85,
            TBCaliber.MEDIUM, 0.95,
            TBCaliber.BIG, 1.0));
    }

    private static void addBlock(Map<ResourceLocation, MaterialStats> map, String raw, MaterialStats stats) {
        ResourceLocation id = ResourceLocation.tryParse(raw.startsWith("#") ? raw.substring(1) : raw);
        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) map.put(id, stats);
        else if (id != null) map.put(id, stats); // Allow blocks supplied by optional mods to resolve when loaded.
    }

    private static void addTag(Map<TagKey<Block>, MaterialStats> map, String raw, MaterialStats stats) {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id != null) map.put(TagKey.create(Registries.BLOCK, id), stats);
    }
}
