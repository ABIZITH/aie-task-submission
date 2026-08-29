# Desert Star E-Invoicing Integration Prototype

Candidate submission for the Associate Integration Engineer — API & ERP Integrations take-home
(UAE E-Invoicing Integration). This is a **local prototype only** — it does not call any Complyance,
customer, sandbox, simulation, or production system, per the assignment's instructions.

## 1. Setup and execution

**Required runtime/tools:** JDK 21 (built entirely with the standard library — `com.sun.net.httpserver`
for HTTP and a small hand-rolled JSON reader/writer in `json/Json.java`). No Maven/Gradle, no external
dependencies. This was a deliberate choice, not an oversight — see "Assumptions and known limitations" below.

```bash
# 1. Compile
find src/main -name "*.java" > sources.txt
javac -d out @sources.txt

# 2. Set the training API key (never hardcoded — read from env var)
export TRAINING_API_KEY=local-dev-key-change-me
# optional: export PORT=8080

# 3. Run
java -cp out com.desertstar.integration.Main
# -> Integration prototype listening on http://localhost:8080
```

### Try it manually

```bash
curl -i -X POST http://localhost:8080/api/v1/invoices \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $TRAINING_API_KEY" \
  -H "Idempotency-Key: demo-001" \
  --data @samples/valid-invoice.json

curl -i http://localhost:8080/api/v1/documents/<documentId-from-above>/status \
  -H "X-Api-Key: $TRAINING_API_KEY"
```

## 2. Test instructions and test-results summary

No test framework is used (the sandbox this was built in has no Maven Central access — see
limitations). Instead, `src/test/java/.../IntegrationTests.java` starts the real server in-process
on port 8099 and drives it with real HTTP calls via `java.net.http.HttpClient`, asserting on status
codes and response content for every Task D scenario.

```bash
mkdir -p test-out
javac -cp out -d test-out src/test/java/com/desertstar/integration/IntegrationTests.java
java -cp out:test-out com.desertstar.integration.IntegrationTests
```

**Result at time of submission: 22/22 assertions passed.**

| # | Scenario | Expected | Result |
|---|---|---|---|
| 1 | Valid standard-rated invoice | `202`, `status=PROCESSING`, later transitions to `ACCEPTED` | ✅ |
| 2 | Missing required seller TRN | `400`, error on `invoice.seller.trn` | ✅ |
| 3 | Invalid TRN format | `400`, `INVALID_FORMAT` | ✅ |
| 4 | Unsupported tax category | `400`, `UNSUPPORTED_VALUE` | ✅ |
| 5 | Incorrect total/tax calculation | `400`, `AMOUNT_MISMATCH` | ✅ |
| 6 | Duplicate retry, same idempotency key | `202` both times, same `documentId` | ✅ |
| 7 | Idempotency key reused with different payload | `409`, `IDEMPOTENCY_KEY_REUSE` | ✅ |
| 8 | Unknown document ID on status retrieval | `404`, `DOCUMENT_NOT_FOUND` | ✅ |
| 9 | Credit note without original invoice reference | `400`, error on `invoice.originalInvoiceNo` | ✅ |
| 10 | Unauthorized request (wrong/missing API key) | `401`, `UNAUTHORIZED` | ✅ |

A Postman collection covering the same scenarios is in `postman/collection.json`, with a matching
`postman/local-environment.json` pre-populated with the sample payloads as environment variables
(just set `apiKey` to your `TRAINING_API_KEY` value and run against `baseUrl`).

### Additional scenarios — described but not implemented (per assignment scope)

- **Zero-rated, exempt, out-of-scope invoices:** would submit the valid invoice with each line's
  `taxCategory` changed to `ZERO_RATED`/`EXEMPT`/`OUT_OF_SCOPE` and `taxRate` recalculated to 0%,
  asserting `taxAmount = 0` and `202` acceptance — the validator already implements this rate logic,
  it just isn't exercised by a dedicated test yet.
- **Multiple currencies / exchange-rate fields:** would require extending the payload schema first
  (see `docs/discovery-and-design.md` §5); tests would then submit a non-AED invoice with an
  `exchangeRate` field and assert both original-currency and AED-equivalent totals are correct.
- **Bulk submission of up to 10 invoices:** would loop the submit call 10 times with distinct
  `invoiceNo`/idempotency keys and assert each gets its own `documentId`, none collide, and total
  latency stays reasonable — the current single-invoice endpoint handles this today via repeated
  calls; a dedicated batch endpoint was out of scope for the 5–6 hour budget.
- **Temporary downstream failure and retry:** would simulate the async processing step throwing
  (e.g. inject a failure into `scheduleAsyncProcessing`) and assert the document stays in
  `PROCESSING` and a retry succeeds without creating a duplicate.
- **Partial failure in a batch:** relevant once a batch endpoint exists — would assert that one
  invalid invoice in a batch of 10 doesn't block the other 9 from being processed, and that the
  response clearly separates per-invoice outcomes.
- **High-volume/performance behavior:** would run a load test (e.g. a simple loop of concurrent
  submissions) and watch for lock contention on the in-memory store or scheduler thread starvation.
- **File-based and manual-upload validation:** the same `InvoiceValidator`/`InvoiceMapper` classes
  are transport-agnostic (they operate on a parsed `Map`, not on the HTTP layer), so a file-based
  ingestion path would parse each row/record into the same shape and reuse them unchanged.

## 3. Assumptions and known limitations

- **No external dependencies.** Built against plain JDK 21 rather than Spring Boot/Jackson, because
  the environment used to build this had no Maven Central access. This is documented here rather
  than silently worked around — a production version would very likely use a proper framework and
  JSON library instead of the hand-rolled `Json.java`.
- **In-memory storage only.** `DocumentStore` is a `ConcurrentHashMap`, not a database. This means:
  data is lost on restart, and duplicate protection only holds within a single running process (see
  `docs/defect-investigation.md` for how this could cause exactly the symptom described in Task E).
  A production implementation needs SQLite/Postgres with unique constraints on `idempotency_key` and
  on the natural key.
- **Idempotency fallback.** If no `Idempotency-Key` header is supplied, the service falls back to a
  derived key of `invoiceNo|sellerTrn|documentType`. This is a design choice beyond the literal spec,
  made so duplicate protection still works for callers that don't send the header — documented as an
  assumption, not silently invented.
- **Async status transition is simulated.** There is no real downstream e-invoicing platform to call
  (and the assignment explicitly forbids calling one). A background task flips `PROCESSING` to
  `ACCEPTED` after ~800ms; an invoice number ending in `-REJECT` is a deterministic test hook that
  flips to `REJECTED` instead, so the `REJECTED` path is reachable in tests without a real failure
  condition.
- **Rounding method:** all money math uses `BigDecimal`, scale 2, `RoundingMode.HALF_UP` (see
  `model/Money.java`). Chosen as the most common and easiest-to-explain convention; flagged as an
  assumption in case the customer's Finance team uses a different convention (e.g. banker's rounding).
- **Buyer TRN and city are not validated** — the assignment's rule list only requires the seller TRN
  format and doesn't specify buyer-TRN or city rules, so these are passed through without validation
  rather than inventing a rule. Flagged as an open question in `docs/mapping.csv`.
- **Single-node only.** No load balancing/clustering support — see go-live checklist in
  `docs/readiness-and-handover.md`.

## 4. Approximate time spent

~5.5 hours: ~1 hour discovery/design notes and mapping table, ~2.5 hours prototype implementation
(JSON layer, validator, mapper, HTTP server, store), ~1 hour test runner + Postman collection +
sample fixtures, ~1 hour defect investigation, readiness checklist, and README.

## 5. External code, documentation, libraries, or tools used

- No external libraries or dependencies (see "Known limitations" above for why).
- Public references consulted for general UAE e-invoicing context only (not for implementation
  detail beyond the assignment's own simplified rules): the OpenPeppol PINT AE documentation and the
  UAE Federal Tax Authority e-invoicing page, both linked in the assignment's optional reading list.
- Standard JDK documentation (`com.sun.net.httpserver`, `java.math.BigDecimal`, `java.net.http.HttpClient`).

## 6. AI-use disclosure

Generative AI (Claude) was used as a coding assistant during this exercise: for scaffolding
boilerplate (the HTTP routing skeleton, the JSON parser structure), for drafting portions of the
documentation prose, and for generating the sample/invalid-invoice fixtures programmatically from
the base valid invoice. All validation rules, mapping decisions, duplicate-handling design, error
codes, and the overall architecture were directed and reviewed by the candidate, who can walk
through and explain every design decision and line of business logic on request, per the assignment's
requirements.

## 7. Candidate declaration

- I used only synthetic data supplied in this exercise.
- I did not include credentials or confidential third-party information.
- I have listed material external resources, reused code, and tools (see section 5).
- I have disclosed use of generative AI (see section 6).
- I can explain the submitted design and code during a follow-up discussion.
