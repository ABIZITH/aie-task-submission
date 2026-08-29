# Defect Investigation: INV-2025-1001 duplicate submission after timeout

**Customer report:** "We submitted invoice INV-2025-1001 twice after a timeout. We received two different document IDs, and one request later failed because the tax total did not match."

This note treats the report as an incident to investigate, not a confirmed product defect — there isn't yet enough evidence to say where the problem originates (customer retry logic, network layer, or the integration service).

## Clarifying questions

1. Were both submissions sent with the same `Idempotency-Key` header, different keys, or no key at all? (This alone could fully explain two document IDs.)
2. What was the exact client-side timeout duration, and did the customer's retry logic wait for a response or fire a second request in parallel?
3. Do the customer have the raw request/response pairs (or at least the two `documentId` values and the correlation IDs) for both attempts?
4. Was the payload byte-for-byte identical between the two submissions, or could a client-side retry have recalculated totals (e.g. reformatted a date, recalculated an FX rate) between attempts?
5. Was the second request the one reported as "failed on tax total," or could both requests have partially succeeded/failed?
6. What time (with timezone) did each request hit our service, per the customer's logs?

## Reproduction steps (to attempt once the above is answered)

1. Submit `samples/valid-invoice.json` with a fresh `Idempotency-Key`, and note the returned `documentId`.
2. Submit the identical payload again with the **same** `Idempotency-Key` — expected: same `documentId`, no new document (per assignment rule and Task D scenario 6).
3. Submit the identical payload again with a **different** `Idempotency-Key` — expected: this prototype's natural-key fallback (invoiceNo + seller TRN + documentType) still returns the original `documentId` rather than creating a duplicate (a design choice beyond the literal spec — see `docs/discovery-and-design.md`).
4. Submit a payload with the same `invoiceNo` but a deliberately incorrect `taxAmount` — expected: `400 AMOUNT_MISMATCH`, no document created.
5. Compare against what the customer actually reported: two different `documentId`s implies neither the idempotency-key path nor the natural-key fallback path was hit — worth checking whether their two requests used two different `invoiceNo` values, two different seller TRNs, or hit two different service instances backed by separate in-memory stores (see "likely causes" below).

## Evidence to collect

- Correlation IDs and timestamps for both attempts, from our structured logs (never the raw payload — logs deliberately exclude full invoice bodies).
- The `Idempotency-Key` header value (if any) sent on each attempt.
- The two `documentId` values returned, and their current status via `GET /api/v1/documents/{id}/status`.
- Confirmation of how many service instances were running at the time (if load-balanced across instances with independent in-memory stores, that alone explains two document IDs — see below).
- The client-side retry/timeout configuration (timeout value, retry count, backoff).

## Likely causes to investigate (not yet confirmed)

- **No idempotency key supplied, or a different key per attempt** — without a stable key, the fallback natural-key check only protects same-process duplicates; if the customer's two requests differ in any field used to build the natural key, both would be treated as new.
- **Multiple service instances with independent in-memory stores** — this prototype's storage is in-memory and single-process; if deployed with more than one instance behind a load balancer, each instance has its own store, and a retry landing on a different instance would look "new" to it. This is the most likely root cause if idempotency keys *were* consistent.
- **Client retried after a timeout but the first request had actually succeeded server-side** — a classic "slow success" scenario: the server processed request 1, but the response was lost/delayed, so the client's timeout looks like a failure it should retry.
- **The second request's tax total genuinely didn't match** — possible if the client recalculated something differently on retry (e.g. reformatted the payload), which is a client-side data issue rather than an integration-service defect.

## Immediate containment / workaround

- Advise the customer to always resubmit with the **same** `Idempotency-Key** for retries of the same logical invoice, and to treat a `409 IDEMPOTENCY_KEY_REUSE` (not applicable here) or a duplicate `202` with the same `documentId` as "already accepted, don't resubmit."
- If duplicate documents were created, manually reconcile: keep the `ACCEPTED` one, flag the extra as a duplicate for the customer's records.

## Recommended permanent controls

- Require the `Idempotency-Key` header (rather than allowing the natural-key fallback) for production, and document this clearly to the customer's ERP team as an integration requirement.
- If horizontal scaling is needed, move from in-memory storage to a shared database with a unique constraint on `idempotency_key`, so duplicate protection holds across instances, not just within one process.
- Add a client-facing retry guide to the handover docs: fixed backoff, same idempotency key, cap on retry attempts.

## What to escalate, and to whom

- **Technical Implementation Consultant:** confirm with the customer exactly what idempotency key strategy (if any) their ERP's retry logic uses today, and whether the environment runs multiple service instances.
- **Engineering:** if multiple instances with independent in-memory stores turns out to be the cause, this is a genuine architecture gap (not just a client misconfiguration) that needs the shared-database fix before go-live, not just a documentation fix.
- **Product:** whether idempotency keys should be made a hard requirement (reject requests without one) rather than an optional-with-fallback field, given this incident.
