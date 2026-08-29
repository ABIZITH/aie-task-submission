package com.desertstar.integration;

import com.desertstar.integration.http.ApiServer;
import com.desertstar.integration.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the whole prototype in-process on a random-ish port and drives it with
 * real HTTP calls, asserting on status codes and response shape. This is not
 * JUnit (no Maven Central access in the build sandbox) but exercises exactly
 * the same code path a Postman collection or CI job would.
 *
 * Run with: java -cp out:test-out com.desertstar.integration.IntegrationTests
 */
public final class IntegrationTests {

    private static final String API_KEY = "test-training-key-123";
    private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static String baseUrl;
    private static final AtomicInteger pass = new AtomicInteger();
    private static final AtomicInteger fail = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        int port = 8099;
        baseUrl = "http://localhost:" + port;
        ApiServer server = new ApiServer(port, API_KEY);
        server.start();
        try {
            Thread.sleep(200);
            String validInvoice = Files.readString(Path.of("samples/valid-invoice.json"));

            // 1. Valid standard-rated invoice -> 202
            HttpResponse<String> r1 = submit(validInvoice, uuid(), API_KEY);
            check("1. Valid invoice returns 202", r1.statusCode() == 202);
            Map<String, Object> r1Body = Json.parseObject(r1.body());
            check("1. Valid invoice returns documentId + PROCESSING", "PROCESSING".equals(r1Body.get("status")));
            String documentId = (String) r1Body.get("documentId");

            // wait for async transition then check status endpoint
            Thread.sleep(1200);
            HttpResponse<String> statusResp = status(documentId, API_KEY);
            check("1b. Status transitions to ACCEPTED", statusResp.statusCode() == 200
                    && "ACCEPTED".equals(Json.parseObject(statusResp.body()).get("status")));

            // 2. Missing required seller TRN -> 400
            String missingTrn = readSample("missing-seller-trn.json");
            HttpResponse<String> r2 = submit(missingTrn, uuid(), API_KEY);
            check("2. Missing seller TRN returns 400", r2.statusCode() == 400);
            check("2. Missing seller TRN error mentions field", r2.body().contains("invoice.seller.trn"));

            // 3. Invalid TRN format -> 400
            String badTrn = readSample("invalid-trn-format.json");
            HttpResponse<String> r3 = submit(badTrn, uuid(), API_KEY);
            check("3. Invalid TRN format returns 400", r3.statusCode() == 400);
            check("3. Invalid TRN format error code present", r3.body().contains("INVALID_FORMAT"));

            // 4. Unsupported tax category -> 400
            String badCategory = readSample("unsupported-tax-category.json");
            HttpResponse<String> r4 = submit(badCategory, uuid(), API_KEY);
            check("4. Unsupported tax category returns 400", r4.statusCode() == 400);
            check("4. Unsupported tax category error code present", r4.body().contains("UNSUPPORTED_VALUE"));

            // 5. Incorrect total / tax calculation -> 400
            String badTotal = readSample("incorrect-total.json");
            HttpResponse<String> r5 = submit(badTotal, uuid(), API_KEY);
            check("5. Incorrect total returns 400", r5.statusCode() == 400);
            check("5. Incorrect total error code present", r5.body().contains("AMOUNT_MISMATCH"));

            // 6. Duplicate retry using same idempotency key -> same documentId, still 202, no new doc
            String key6 = uuid();
            HttpResponse<String> r6a = submit(validInvoice, key6, API_KEY, "INV-DUP-0001");
            HttpResponse<String> r6b = submit(validInvoice, key6, API_KEY, "INV-DUP-0001");
            String id6a = (String) Json.parseObject(r6a.body()).get("documentId");
            String id6b = (String) Json.parseObject(r6b.body()).get("documentId");
            check("6. Duplicate retry (same key) returns 202 both times", r6a.statusCode() == 202 && r6b.statusCode() == 202);
            check("6. Duplicate retry (same key) returns the same documentId", id6a.equals(id6b));

            // 7. Reuse of idempotency key with a DIFFERENT payload -> 409
            String key7 = uuid();
            HttpResponse<String> r7a = submit(validInvoice, key7, API_KEY, "INV-CONFLICT-0001");
            String differentPayload = withInvoiceNo(validInvoice, "INV-CONFLICT-0002");
            HttpResponse<String> r7b = submit(differentPayload, key7, API_KEY);
            check("7. First request with new key returns 202", r7a.statusCode() == 202);
            check("7. Reused key with different payload returns 409", r7b.statusCode() == 409);
            check("7. Conflict error code present", r7b.body().contains("IDEMPOTENCY_KEY_REUSE"));

            // 8. Unknown document ID on status retrieval -> 404
            HttpResponse<String> r8 = status("00000000-0000-0000-0000-000000000000", API_KEY);
            check("8. Unknown document ID returns 404", r8.statusCode() == 404);
            check("8. Unknown document ID error code present", r8.body().contains("DOCUMENT_NOT_FOUND"));

            // 9. Credit note without original invoice reference -> 400
            String badCreditNote = readSample("credit-note-missing-original.json");
            HttpResponse<String> r9 = submit(badCreditNote, uuid(), API_KEY);
            check("9. Credit note missing original ref returns 400", r9.statusCode() == 400);
            check("9. Credit note error mentions originalInvoiceNo", r9.body().contains("originalInvoiceNo"));

            // 10. Unauthorized request -> 401
            HttpResponse<String> r10 = submit(validInvoice, uuid(), "wrong-key");
            check("10. Wrong API key returns 401", r10.statusCode() == 401);
            HttpResponse<String> r10b = submit(validInvoice, uuid(), null);
            check("10b. Missing API key returns 401", r10b.statusCode() == 401);

        } finally {
            server.stop();
        }

        System.out.println();
        System.out.println("=== Test summary: " + pass.get() + " passed, " + fail.get() + " failed ===");
        System.exit(fail.get() > 0 ? 1 : 0);
    }

    private static String readSample(String name) throws Exception {
        return Files.readString(Path.of("samples/invalid-invoices/" + name));
    }

    private static String withInvoiceNo(String json, String invoiceNo) {
        return json.replaceFirst("\"invoiceNo\"\\s*:\\s*\"[^\"]*\"", "\"invoiceNo\": \"" + invoiceNo + "\"");
    }

    private static HttpResponse<String> submit(String body, String idempotencyKey, String apiKey) throws Exception {
        return submit(body, idempotencyKey, apiKey, null);
    }

    private static HttpResponse<String> submit(String body, String idempotencyKey, String apiKey, String invoiceNoOverride) throws Exception {
        String payload = invoiceNoOverride == null ? body : withInvoiceNo(body, invoiceNoOverride);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/invoices"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (apiKey != null) b.header("X-Api-Key", apiKey);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> status(String documentId, String apiKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/documents/" + documentId + "/status"))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (apiKey != null) b.header("X-Api-Key", apiKey);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static void check(String description, boolean condition) {
        if (condition) {
            pass.incrementAndGet();
            System.out.println("PASS - " + description);
        } else {
            fail.incrementAndGet();
            System.out.println("FAIL - " + description);
        }
    }
}
