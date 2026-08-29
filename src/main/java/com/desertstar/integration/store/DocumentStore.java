package com.desertstar.integration.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory persistence for the prototype.
 *
 * PRODUCTION NOTE (see README "Known limitations"): an in-memory Map is lost on
 * restart and cannot be shared across instances. A production implementation
 * needs a real database (e.g. Postgres/SQLite) with a unique constraint on
 * idempotency_key, a unique constraint on the natural key (invoiceNo + sellerTrn
 * + documentType), indexes for status lookups, and a retention/archival policy
 * for completed documents.
 *
 * Two indexes are kept:
 *  - by idempotencyKey: the primary duplicate-retry control (assignment rule:
 *    "the same invoice must not be processed twice when an identical request
 *    is retried").
 *  - by naturalKey (invoiceNo + sellerTrn + documentType): a secondary guard so
 *    that a *different* idempotency key reused for what is clearly the same
 *    business invoice doesn't silently create a second document. This is a
 *    design choice beyond the literal spec — documented as an assumption in
 *    docs/discovery-and-design.md.
 */
public final class DocumentStore {

    private final Map<String, DocumentRecord> byDocumentId = new ConcurrentHashMap<>();
    private final Map<String, DocumentRecord> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, String> naturalKeyToIdempotencyKey = new ConcurrentHashMap<>();

    public DocumentRecord findByIdempotencyKey(String key) {
        return byIdempotencyKey.get(key);
    }

    public DocumentRecord findByNaturalKey(String naturalKey) {
        String idKey = naturalKeyToIdempotencyKey.get(naturalKey);
        return idKey == null ? null : byIdempotencyKey.get(idKey);
    }

    public DocumentRecord findByDocumentId(String documentId) {
        return byDocumentId.get(documentId);
    }

    public void save(DocumentRecord record, String naturalKey) {
        byDocumentId.put(record.documentId, record);
        byIdempotencyKey.put(record.idempotencyKey, record);
        naturalKeyToIdempotencyKey.putIfAbsent(naturalKey, record.idempotencyKey);
    }
}
