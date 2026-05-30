package com.cbc_terminal_ballistics.data;

import com.cbc_terminal_ballistics.ballistics.TBCaliber;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public record MaterialStats(double toughnessMultiplier, double brittleness, double ductility, double spallMultiplier,
                            Map<TBCaliber, Double> caliberDamageScale) {
    public static final MaterialStats DEFAULT = new MaterialStats(1.0, 0.15, 1.0, 1.0, Map.of());

    public static MaterialStats fromJson(JsonObject obj) {
        double toughness = get(obj, "toughness_multiplier", 1.0);
        double brittle = get(obj, "brittleness", 0.15);
        double ductile = get(obj, "ductility", 1.0);
        double spall = get(obj, "spall_multiplier", 1.0);
        EnumMap<TBCaliber, Double> scale = new EnumMap<>(TBCaliber.class);
        if (obj.has("caliber_damage_scale") && obj.get("caliber_damage_scale").isJsonObject()) {
            JsonObject c = obj.getAsJsonObject("caliber_damage_scale");
            for (Map.Entry<String, JsonElement> e : c.entrySet()) {
                try {
                    scale.put(TBCaliber.valueOf(e.getKey().toUpperCase(Locale.ROOT)), e.getValue().getAsDouble());
                } catch (Exception ignored) {}
            }
        }
        return new MaterialStats(Math.max(0.0, toughness), clamp01(brittle), Math.max(0.05, ductile), Math.max(0.0, spall), scale);
    }

    public double caliberScale(TBCaliber caliber) {
        return this.caliberDamageScale.getOrDefault(caliber, 1.0);
    }

    private static double get(JsonObject obj, String key, double fallback) {
        return obj.has(key) ? obj.get(key).getAsDouble() : fallback;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
