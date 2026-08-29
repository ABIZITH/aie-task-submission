package com.desertstar.integration.http;

import com.desertstar.integration.json.Json;
import com.desertstar.integration.json.JsonPath;
import com.desertstar.integration.logging.SafeLogger;
import com.desertstar.integration.model.InvoiceMapper;
import com.desertstar.integration.model.InvoiceValidator;
import com.desertstar.integration.model.ValidationError;
import com.desertstar.integration.store.DocumentRecord;
import com.desertstar.integration.store.DocumentStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiServer {

    private static final Pattern STATUS_PATH = Pattern.compile("^/api/v1/documents/([^/]+)/status$");

    private final HttpServer server;
    private final DocumentStore store = new DocumentStore();
    private final InvoiceValidator validator = new InvoiceValidator();
    private final InvoiceMapper mapper = new InvoiceMapper();
    private final SafeLogger log = new SafeLogger();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemonFactory());
    private final String apiKey;

    public ApiServer(int port, String apiKey) throws IOException {
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/invoices", this::handleSubmit);
        server.createContext("/api/v1/documents/", this::handleStatus);
        server.setExecutor(Executors.newFixedThreadPool(8, daemonFactory()));
    }

    private static java.util.concurrent.ThreadFactory daemonFactory() {
        return r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        };
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    // ---------- POST /api/v1/invoices ----------

    private void handleSubmit(HttpExchange exchange) throws IOException {
        String correlationId = correlationId(exchange);
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, correlationId, 405, "METHOD_NOT_ALLOWED", "Only POST is supported on this endpoint", null);
                return;
            }
            if (!isAuthorized(exchange)) {
                log.warn("unauthorized_request", correlationId, Map.of("path", "/api/v1/invoices"));
                sendError(exchange, correlationId, 401, "UNAUTHORIZED", "Missing or invalid API key", null);
                return;
            }

            String body = readBody(exchange);
            Map<String, Object> root;
            try {
                root = Json.parseObject(body);
            } catch (Exception e) {
                sendError(exchange, correlationId, 400, "MALFORMED_JSON", "Request body is not valid JSON", null);
                return;
            }

            List<ValidationError> errors = validator.validate(root);
            Map<String, Object> invoice = JsonPath.obj(root, "invoice");
            String invoiceNo = invoice == null ? null : JsonPath.str(invoice, "invoiceNo");

            if (!errors.isEmpty()) {
                log.info("validation_failed", correlationId, Map.of(
                        "invoiceNo", String.valueOf(invoiceNo), "errorCount", errors.size()));
                sendValidationError(exchange, correlationId, errors);
                return;
            }

            String naturalKey = naturalKey(invoice);
            String idempotencyKey = exchange.getRequestHeaders().containsKey("Idempotency-Key")
                    ? exchange.getRequestHeaders().getFirst("Idempotency-Key")
                    : "auto:" + naturalKey; // documented fallback when no header is supplied
            String payloadHash = sha256(body);

            DocumentRecord existing = store.findByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                if (existing.payloadHash.equals(payloadHash)) {
                    log.info("duplicate_retry_returned_existing", correlationId, Map.of(
                            "documentId", existing.documentId, "invoiceNo", existing.invoiceNo));
                    sendJson(exchange, correlationId, 202, submitResponse(existing));
                } else {
                    log.warn("idempotency_key_conflict", correlationId, Map.of("idempotencyKey", idempotencyKey));
                    sendError(exchange, correlationId, 409, "IDEMPOTENCY_KEY_REUSE",
                            "This Idempotency-Key was already used with a different request body", null);
                }
                return;
            }

            DocumentRecord byNaturalKey = store.findByNaturalKey(naturalKey);
            if (byNaturalKey != null) {
                log.info("duplicate_natural_key_returned_existing", correlationId, Map.of(
                        "documentId", byNaturalKey.documentId, "invoiceNo", byNaturalKey.invoiceNo));
                sendJson(exchange, correlationId, 202, submitResponse(byNaturalKey));
                return;
            }

            Map<String, Object> normalized = mapper.map(root);
            String documentId = UUID.randomUUID().toString();
            DocumentRecord record = new DocumentRecord(documentId, invoiceNo, idempotencyKey, payloadHash,
                    normalized, DocumentRecord.Status.PROCESSING, List.of());
            store.save(record, naturalKey);

            log.info("document_accepted_for_processing", correlationId, Map.of(
                    "documentId", documentId, "invoiceNo", String.valueOf(invoiceNo)));

            scheduleAsyncProcessing(record, correlationId);

            sendJson(exchange, correlationId, 202, submitResponse(record));
        } catch (Exception e) {
            log.error("internal_error", correlationId, Map.of("exceptionType", e.getClass().getSimpleName()));
            sendError(exchange, correlationId, 500, "INTERNAL_ERROR", "An unexpected error occurred", null);
        }
    }

    /**
     * Simulates the downstream async status transition. Deterministic test hook:
     * an invoiceNo ending in "-REJECT" flips to REJECTED after the delay; every
     * other valid invoice flips to ACCEPTED. This keeps Task D scenarios
     * reproducible without a real downstream system (documented in README).
     */
    private void scheduleAsyncProcessing(DocumentRecord record, String correlationId) {
        scheduler.schedule(() -> {
            boolean simulateRejection = record.invoiceNo != null && record.invoiceNo.endsWith("-REJECT");
            record.status = simulateRejection ? DocumentRecord.Status.REJECTED : DocumentRecord.Status.ACCEPTED;
            if (simulateRejection) {
                record.errors = List.of(new ValidationError("invoice", "DOWNSTREAM_REJECTED",
                        "Simulated downstream rejection for testing"));
            }
            record.updatedAt = Instant.now();
            log.info("document_status_transitioned", correlationId, Map.of(
                    "documentId", record.documentId, "status", record.status.name()));
        }, 800, TimeUnit.MILLISECONDS);
    }

    private Map<String, Object> submitResponse(DocumentRecord record) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", record.documentId);
        m.put("status", record.status.name());
        m.put("isTerminal", record.status != DocumentRecord.Status.PROCESSING);
        m.put("receivedAt", record.createdAt.toString());
        return m;
    }

    // ---------- GET /api/v1/documents/{id}/status ----------

    private void handleStatus(HttpExchange exchange) throws IOException {
        String correlationId = correlationId(exchange);
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, correlationId, 405, "METHOD_NOT_ALLOWED", "Only GET is supported on this endpoint", null);
                return;
            }
            if (!isAuthorized(exchange)) {
                sendError(exchange, correlationId, 401, "UNAUTHORIZED", "Missing or invalid API key", null);
                return;
            }
            Matcher m = STATUS_PATH.matcher(exchange.getRequestURI().getPath());
            if (!m.matches()) {
                sendError(exchange, correlationId, 404, "NOT_FOUND", "Unknown route", null);
                return;
            }
            String documentId = m.group(1);
            DocumentRecord record = store.findByDocumentId(documentId);
            if (record == null) {
                log.info("status_lookup_not_found", correlationId, Map.of("documentId", documentId));
                sendError(exchange, correlationId, 404, "DOCUMENT_NOT_FOUND", "No document found for this ID", null);
                return;
            }
            sendJson(exchange, correlationId, 200, record.toStatusJson());
        } catch (Exception e) {
            log.error("internal_error", correlationId, Map.of("exceptionType", e.getClass().getSimpleName()));
            sendError(exchange, correlationId, 500, "INTERNAL_ERROR", "An unexpected error occurred", null);
        }
    }

    // ---------- shared helpers ----------

    private boolean isAuthorized(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Api-Key");
        return apiKey != null && !apiKey.isEmpty() && apiKey.equals(provided);
    }

    private String naturalKey(Map<String, Object> invoice) {
        if (invoice == null) return "unknown";
        Map<String, Object> seller = JsonPath.obj(invoice, "seller");
        String sellerTrn = seller == null ? "" : String.valueOf(JsonPath.str(seller, "trn"));
        return JsonPath.str(invoice, "invoiceNo") + "|" + sellerTrn + "|" + JsonPath.str(invoice, "documentType");
    }

    private String correlationId(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        return (provided == null || provided.isBlank()) ? UUID.randomUUID().toString() : provided;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int read;
        while ((read = is.read(data)) != -1) buffer.write(data, 0, read);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, String correlationId, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendValidationError(HttpExchange exchange, String correlationId, List<ValidationError> errors) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "VALIDATION_FAILED");
        error.put("message", "One or more fields failed validation");
        error.put("correlationId", correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        List<Object> errJson = new java.util.ArrayList<>();
        for (ValidationError e : errors) errJson.add(e.toJson());
        body.put("errors", errJson);

        sendJson(exchange, correlationId, 400, body);
    }

    private void sendError(HttpExchange exchange, String correlationId, int status, String code, String message,
                            List<ValidationError> errors) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("correlationId", correlationId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (errors != null) {
            List<Object> errJson = new java.util.ArrayList<>();
            for (ValidationError e : errors) errJson.add(e.toJson());
            body.put("errors", errJson);
        }
        sendJson(exchange, correlationId, status, body);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
