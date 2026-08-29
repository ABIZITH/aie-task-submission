# System Flow

See `discovery-and-design.md` section 3 for narrative context. Diagram below (Mermaid).

```mermaid
flowchart LR
    ERP[Customer ERP<br/>Desert Star Trading LLC] -->|POST /api/v1/invoices<br/>JSON + API key| SVC[Integration Service]
    SVC --> AUTH{API key valid?}
    AUTH -->|No| REJECT401[401 Unauthorized]
    AUTH -->|Yes| VALMAP[Validation & Mapping<br/>simplified assessment rules]
    VALMAP -->|Invalid| REJECT400[400 + structured errors]
    VALMAP -->|Valid| DEDUP{Idempotency /<br/>duplicate check}
    DEDUP -->|Duplicate| RETURN_EXISTING[Return existing document<br/>202, same documentId]
    DEDUP -->|New| STORE[(Document Storage<br/>SQLite/in-memory)]
    STORE --> ASYNC[Async Processing<br/>PROCESSING to ACCEPTED/REJECTED]
    ASYNC --> STORE
    ERP -->|GET /api/v1/documents/id/status| STATUS[Status Endpoint]
    STATUS --> STORE
    SVC -.logs/metrics.-> MON[Monitoring & Support<br/>correlation IDs, structured logs]
```

**Legend / component notes**
- **Integration Service** — the Java prototype in `src/`, exposing the two REST endpoints.
- **Validation & Mapping** — `InvoiceValidator` (assessment rules) and `InvoiceMapper` (source-to-normalized transform).
- **Document Storage** — `DocumentStore`, currently in-memory; see README "Known limitations" for the production swap-in.
- **Async Processing** — a scheduled task simulating a downstream acceptance/rejection decision (real system would call the actual e-invoicing platform here — which this prototype explicitly does not do, per the assignment's "do not call any... environment" rule).
- **Monitoring & Support** — structured JSON logs keyed by correlation ID; see `logging/SafeLogger.java`.
