package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.CBCTerminalBallistics;
import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MaterialManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final double METALLIC_TOUGHNESS_FALLBACK = 10.0D;
    public static final MaterialManager INSTANCE = new MaterialManager();

    private volatile Map<ResourceLocation, MaterialStats> byBlock = Map.of();
    private volatile Map<TagKey<Block>, MaterialStats> byTag = Map.of();
    private volatile List<NamespaceRule> namespaceRules = List.of();
    private volatile List<PathRule> pathRules = List.of();

    private MaterialManager() {
        super(GSON, "terminal_ballistics/materials");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, MaterialStats> blocks = new HashMap<>();
        Map<TagKey<Block>, MaterialStats> tags = new HashMap<>();
        List<NamespaceRule> namespaces = new ArrayList<>();
        List<PathRule> paths = new ArrayList<>();
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
                if (obj.has("namespace")) namespaces.add(new NamespaceRule(obj.get("namespace").getAsString(), stats));
                if (obj.has("namespaces") && obj.get("namespaces").isJsonArray()) {
                    obj.getAsJsonArray("namespaces").forEach(e -> namespaces.add(new NamespaceRule(e.getAsString(), stats)));
                }
                if (obj.has("path_contains")) paths.add(new PathRule(obj.get("path_contains").getAsString(), stats));
                if (obj.has("path_contains_any") && obj.get("path_contains_any").isJsonArray()) {
                    obj.getAsJsonArray("path_contains_any").forEach(e -> paths.add(new PathRule(e.getAsString(), stats)));
                }
                // If no selector is present, treat the file id itself as a block id for compact datapacks.
                if (!obj.has("block") && !obj.has("blocks") && !obj.has("tag") && !obj.has("tags")
                    && !obj.has("namespace") && !obj.has("namespaces") && !obj.has("path_contains") && !obj.has("path_contains_any")) {
                    addBlock(blocks, entry.getKey().toString(), stats);
                }
            } catch (Exception ex) {
                CBCTerminalBallistics.LOGGER.warn("Failed to load terminal ballistics material {}", entry.getKey(), ex);
            }
        }
        this.byBlock = Map.copyOf(blocks);
        this.byTag = Map.copyOf(tags);
        this.namespaceRules = List.copyOf(namespaces);
        this.pathRules = List.copyOf(paths);
        CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] Loaded {} block, {} tag, {} namespace and {} path terminal-ballistics material mappings",
            blocks.size(), tags.size(), namespaces.size(), paths.size());
    }

    public int blockMappingCount() { return this.byBlock.size(); }
    public int tagMappingCount() { return this.byTag.size(); }

    public MaterialStats get(BlockState state) {
        return get(state, 0.0D);
    }

    public MaterialStats get(BlockState state, double resolvedArmorToughness) {
        MaterialStats explicit = explicit(state);
        if (explicit != null) return explicit;
        return fallback(resolvedArmorToughness);
    }

    public ImpactSurfaceType surface(BlockState state, double resolvedArmorToughness) {
        return get(state, resolvedArmorToughness).surface();
    }

    private MaterialStats explicit(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        MaterialStats direct = this.byBlock.get(blockId);
        if (direct != null) return direct;
        for (Map.Entry<TagKey<Block>, MaterialStats> entry : this.byTag.entrySet()) {
            if (state.is(entry.getKey())) return entry.getValue();
        }
        String ns = blockId.getNamespace();
        String path = blockId.getPath();
        for (NamespaceRule rule : this.namespaceRules) {
            if (ns.equals(rule.namespace())) return rule.stats();
        }
        for (PathRule rule : this.pathRules) {
            if (path.contains(rule.needle())) return rule.stats();
        }
        return null;
    }

    private static MaterialStats fallback(double resolvedArmorToughness) {
        return resolvedArmorToughness >= METALLIC_TOUGHNESS_FALLBACK ? MaterialStats.METALLIC_FALLBACK : MaterialStats.GENERAL_FALLBACK;
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

    private record NamespaceRule(String namespace, MaterialStats stats) {}
    private record PathRule(String needle, MaterialStats stats) {}
}
