package com.desertstar.integration.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A single field-level validation failure. Kept structured so clients can branch on {@code code}. */
public final class ValidationError {

    public final String field;
    public final String code;
    public final String message;

    public ValidationError(String field, String code, String message) {
        this.field = field;
        this.code = code;
        this.message = message;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("code", code);
        m.put("message", message);
        return m;
    }
}
