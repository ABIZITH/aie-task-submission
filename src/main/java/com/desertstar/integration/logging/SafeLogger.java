package com.desertstar.integration.logging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits single-line structured (JSON-ish) logs to stdout.
 *
 * Hard rule enforced by convention here: callers must never pass the raw
 * request body, the raw API key, or full seller/buyer/line detail into a log
 * line. Only identifiers (correlationId, documentId, invoiceNo) and outcome
 * metadata (status, error codes, elapsed time) are logged. See
 * docs/discovery-and-design.md for the reasoning.
 */
public final class SafeLogger {

    public void info(String event, String correlationId, Map<String, Object> fields) {
        write("INFO", event, correlationId, fields);
    }

    public void warn(String event, String correlationId, Map<String, Object> fields) {
        write("WARN", event, correlationId, fields);
    }

    public void error(String event, String correlationId, Map<String, Object> fields) {
        write("ERROR", event, correlationId, fields);
    }

    private void write(String level, String event, String correlationId, Map<String, Object> fields) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("level", level);
        line.put("event", event);
        line.put("correlationId", correlationId);
        if (fields != null) line.putAll(fields);
        System.out.println(com.desertstar.integration.json.Json.write(line));
    }
}
