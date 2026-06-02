package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.ballistics.ImpactSurfaceType;
import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public record MaterialStats(ImpactSurfaceType surface, double toughnessMultiplier, double brittleness, double ductility, double spallMultiplier,
                            Map<TBCaliber, Double> caliberDamageScale) {
    public static final MaterialStats GENERAL_FALLBACK = fallback(ImpactSurfaceType.GENERAL);
    public static final MaterialStats METALLIC_FALLBACK = fallback(ImpactSurfaceType.METALLIC);
    public static final MaterialStats DEFAULT = GENERAL_FALLBACK;

    public static MaterialStats fallback(ImpactSurfaceType surface) {
        return switch (surface) {
            case METALLIC -> new MaterialStats(ImpactSurfaceType.METALLIC, 1.0, 0.12, 4.0, 0.9, Map.of());
            case GENERAL -> new MaterialStats(ImpactSurfaceType.GENERAL, 1.0, 0.45, 0.75, 0.45, Map.of());
        };
    }

    public static MaterialStats fromJson(JsonObject obj) {
        ImpactSurfaceType surface = surface(obj);
        MaterialStats fallback = fallback(surface);
        double toughness = get(obj, "toughness_multiplier", fallback.toughnessMultiplier());
        double brittle = get(obj, "brittleness", fallback.brittleness());
        double ductile = get(obj, "ductility", fallback.ductility());
        double spall = get(obj, "spall_multiplier", fallback.spallMultiplier());
        EnumMap<TBCaliber, Double> scale = new EnumMap<>(TBCaliber.class);
        if (obj.has("caliber_damage_scale") && obj.get("caliber_damage_scale").isJsonObject()) {
            JsonObject c = obj.getAsJsonObject("caliber_damage_scale");
            for (Map.Entry<String, JsonElement> e : c.entrySet()) {
                try {
                    scale.put(TBCaliber.valueOf(e.getKey().toUpperCase(Locale.ROOT)), e.getValue().getAsDouble());
                } catch (Exception ignored) {}
            }
        }
        return new MaterialStats(surface, Math.max(0.0, toughness), clamp01(brittle), Math.max(0.05, ductile), Math.max(0.0, spall), scale);
    }

    public double caliberScale(TBCaliber caliber) {
        return this.caliberDamageScale.getOrDefault(caliber, 1.0);
    }

    private static ImpactSurfaceType surface(JsonObject obj) {
        String raw = obj.has("surface") ? obj.get("surface").getAsString() : obj.has("type") ? obj.get("type").getAsString() : "general";
        return raw.equalsIgnoreCase("metal") || raw.equalsIgnoreCase("metallic") ? ImpactSurfaceType.METALLIC : ImpactSurfaceType.GENERAL;
    }

    private static double get(JsonObject obj, String key, double fallback) {
        return obj.has(key) ? obj.get(key).getAsDouble() : fallback;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
