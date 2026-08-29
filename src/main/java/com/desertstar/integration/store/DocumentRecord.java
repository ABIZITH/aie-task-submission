package com.desertstar.integration.store;

import com.desertstar.integration.model.ValidationError;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DocumentRecord {

    public enum Status { PROCESSING, ACCEPTED, REJECTED }

    public final String documentId;
    public final String invoiceNo;
    public final String idempotencyKey;
    public final String payloadHash;
    public final Map<String, Object> normalizedInvoice;
    public volatile Status status;
    public volatile List<ValidationError> errors;
    public final Instant createdAt;
    public volatile Instant updatedAt;

    public DocumentRecord(String documentId, String invoiceNo, String idempotencyKey, String payloadHash,
                           Map<String, Object> normalizedInvoice, Status status, List<ValidationError> errors) {
        this.documentId = documentId;
        this.invoiceNo = invoiceNo;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.normalizedInvoice = normalizedInvoice;
        this.status = status;
        this.errors = errors;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Map<String, Object> toStatusJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documentId", documentId);
        m.put("status", status.name());
        m.put("isTerminal", status != Status.PROCESSING);
        List<Object> errJson = new java.util.ArrayList<>();
        if (errors != null) {
            for (ValidationError e : errors) errJson.add(e.toJson());
        }
        m.put("errors", errJson);
        return m;
    }
}
