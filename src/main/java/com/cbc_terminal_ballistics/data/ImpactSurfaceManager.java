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

public class ImpactSurfaceManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final double METALLIC_TOUGHNESS_FALLBACK = 10.0D;
    public static final ImpactSurfaceManager INSTANCE = new ImpactSurfaceManager();

    private volatile Map<ResourceLocation, ImpactSurfaceType> byBlock = Map.of();
    private volatile Map<TagKey<Block>, ImpactSurfaceType> byTag = Map.of();
    private volatile List<NamespaceRule> namespaceRules = List.of();
    private volatile List<PathRule> pathRules = List.of();

    private ImpactSurfaceManager() {
        super(GSON, "terminal_ballistics/impact_surfaces");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, ImpactSurfaceType> blocks = new HashMap<>();
        Map<TagKey<Block>, ImpactSurfaceType> tags = new HashMap<>();
        List<NamespaceRule> namespaces = new ArrayList<>();
        List<PathRule> paths = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : jsons.entrySet()) {
            try {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject obj = entry.getValue().getAsJsonObject();
                ImpactSurfaceType type = type(obj);
                if (obj.has("block")) addBlock(blocks, obj.get("block").getAsString(), type);
                if (obj.has("blocks") && obj.get("blocks").isJsonArray()) {
                    obj.getAsJsonArray("blocks").forEach(e -> addBlock(blocks, e.getAsString(), type));
                }
                if (obj.has("tag")) addTag(tags, obj.get("tag").getAsString(), type);
                if (obj.has("tags") && obj.get("tags").isJsonArray()) {
                    obj.getAsJsonArray("tags").forEach(e -> addTag(tags, e.getAsString(), type));
                }
                if (obj.has("namespace")) namespaces.add(new NamespaceRule(obj.get("namespace").getAsString(), type));
                if (obj.has("namespaces") && obj.get("namespaces").isJsonArray()) {
                    obj.getAsJsonArray("namespaces").forEach(e -> namespaces.add(new NamespaceRule(e.getAsString(), type)));
                }
                if (obj.has("path_contains")) paths.add(new PathRule(obj.get("path_contains").getAsString(), type));
                if (obj.has("path_contains_any") && obj.get("path_contains_any").isJsonArray()) {
                    obj.getAsJsonArray("path_contains_any").forEach(e -> paths.add(new PathRule(e.getAsString(), type)));
                }
                if (!obj.has("block") && !obj.has("blocks") && !obj.has("tag") && !obj.has("tags")
                    && !obj.has("namespace") && !obj.has("namespaces") && !obj.has("path_contains") && !obj.has("path_contains_any")) {
                    addBlock(blocks, entry.getKey().toString(), type);
                }
            } catch (Exception ex) {
                CBCTerminalBallistics.LOGGER.warn("Failed to load impact surface mapping {}", entry.getKey(), ex);
            }
        }
        this.byBlock = Map.copyOf(blocks);
        this.byTag = Map.copyOf(tags);
        this.namespaceRules = List.copyOf(namespaces);
        this.pathRules = List.copyOf(paths);
        CBCTerminalBallistics.LOGGER.warn("[CTB-DEBUG] Loaded {} block, {} tag, {} namespace and {} path impact-surface mappings",
            blocks.size(), tags.size(), namespaces.size(), paths.size());
    }

    public ImpactSurfaceType get(BlockState state, double resolvedArmorToughness) {
        ImpactSurfaceType explicit = explicit(state);
        if (explicit != null) return explicit;
        return resolvedArmorToughness >= METALLIC_TOUGHNESS_FALLBACK ? ImpactSurfaceType.METALLIC : ImpactSurfaceType.GENERAL;
    }

    private ImpactSurfaceType explicit(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        ImpactSurfaceType direct = this.byBlock.get(id);
        if (direct != null) return direct;
        for (Map.Entry<TagKey<Block>, ImpactSurfaceType> entry : this.byTag.entrySet()) {
            if (state.is(entry.getKey())) return entry.getValue();
        }
        String ns = id.getNamespace();
        String path = id.getPath();
        for (NamespaceRule rule : this.namespaceRules) {
            if (ns.equals(rule.namespace())) return rule.type();
        }
        for (PathRule rule : this.pathRules) {
            if (path.contains(rule.needle())) return rule.type();
        }
        return null;
    }

    private static ImpactSurfaceType type(JsonObject obj) {
        String raw = obj.has("type") ? obj.get("type").getAsString() : obj.has("surface") ? obj.get("surface").getAsString() : "general";
        return raw.equalsIgnoreCase("metal") || raw.equalsIgnoreCase("metallic") ? ImpactSurfaceType.METALLIC : ImpactSurfaceType.GENERAL;
    }

    private static void addBlock(Map<ResourceLocation, ImpactSurfaceType> map, String raw, ImpactSurfaceType type) {
        ResourceLocation id = ResourceLocation.tryParse(raw.startsWith("#") ? raw.substring(1) : raw);
        if (id != null) map.put(id, type);
    }

    private static void addTag(Map<TagKey<Block>, ImpactSurfaceType> map, String raw, ImpactSurfaceType type) {
        String s = raw.startsWith("#") ? raw.substring(1) : raw;
        ResourceLocation id = ResourceLocation.tryParse(s);
        if (id != null) map.put(TagKey.create(Registries.BLOCK, id), type);
    }

    private record NamespaceRule(String namespace, ImpactSurfaceType type) {}
    private record PathRule(String needle, ImpactSurfaceType type) {}
}
