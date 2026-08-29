# Delivery Readiness and Handover Checklist

## UAT readiness
- [ ] Requirements and `docs/mapping.csv` reviewed and signed off by the customer's Finance/IT stakeholders.
- [ ] UAT environment reachable and connectivity confirmed (URL, network access, firewall rules if applicable).
- [ ] Training API key provisioned to the customer out-of-band (never via email/source control) and rotated post-UAT.
- [ ] Synthetic/approved test data agreed with the customer — no real seller/buyer TRNs or production invoice data used.
- [ ] Positive scenario (valid invoice) and all Task D negative scenarios executed and results captured.
- [ ] Defect ownership and severity levels agreed (who triages, what counts as blocking vs. non-blocking).
- [ ] Exit criteria agreed and met: e.g. "N consecutive valid submissions processed correctly, 0 open blocking defects."

## Go-live readiness
- [ ] Production configuration separated from UAT (distinct API keys, distinct storage, distinct URLs).
- [ ] Credentials provisioned securely (secrets manager or equivalent — never committed, never emailed).
- [ ] Mapping and validation rules confirmed as version-aligned with what was tested in UAT (no last-minute changes untested).
- [ ] Smoke test plan defined for go-live day (submit one known-good invoice, confirm status transitions correctly).
- [ ] Monitoring/alerting in place for error-rate spikes and stuck `PROCESSING` documents.
- [ ] Retry, reconciliation, and duplicate-prevention behavior explained to the customer's ERP team, including the idempotency-key requirement.
- [ ] Rollback or fallback plan defined (e.g. temporarily route invoices to manual review if the service is unavailable).
- [ ] Support ownership and escalation contacts documented and shared with the customer.

## Hypercare and support handover
- [ ] Known issues at go-live documented (including any prototype-only limitations, e.g. in-memory storage, single-instance duplicate protection).
- [ ] Monitoring and alerting thresholds documented for the support team taking over.
- [ ] Runbook/troubleshooting guide covers: how to read correlation IDs in logs, how to look up a document's status, how to identify an idempotency-key conflict.
- [ ] Open actions from UAT/go-live tracked with owners and target dates.
- [ ] Customer and internal support contacts listed with escalation paths.
- [ ] Exit criteria from hypercare agreed (e.g. "X days with no Sev-1/Sev-2 incidents") before handing off to steady-state support.
