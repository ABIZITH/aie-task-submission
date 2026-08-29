package com.desertstar.integration.json;

import java.util.List;
import java.util.Map;

/** Convenience accessors for reading values out of a parsed JSON Map without a cascade of casts. */
public final class JsonPath {

    private JsonPath() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }

    public static String str(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v == null ? null : v.toString();
    }

    public static Double num(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
