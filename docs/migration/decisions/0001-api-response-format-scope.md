# ADR 0001 — Response-format scope for `/api/v1`

- **Status**: Accepted, pending log-based confirmation (owner: deployment operator)
- **Date**: 2026-08-21
- **Phase**: 0 (constrains Phase 4 read API and Phase 6 track H6b)

## Context

`ApplicationController` declares eight response formats for every inherited controller
(`html`, `js`, `json`, `xml`, `atom`, `rss`, `csv`, `xls`) plus `vcf` on two `show` actions.
The full audit is in [`../api-format-inventory.md`](../api-format-inventory.md). The breadth is
inherited-declaration fallout, not evidence of use: `xml` and `atom`/`rss` are answered by
`respond_with` on actions that have no dedicated template, while CSV, XLS and vCard are
deliberate, user-facing exports with hand-written projections.

Porting a format is not free — each one is a content negotiator, a projection, and a set of
harness assertions in Phase 4/6.

## Decision

For `/api/v1` served by Spring Boot:

| Format | Decision | Notes |
|---|---|---|
| JSON | **Keep** — the only first-class format | Matches `openapi.yaml` (ADR 0005) |
| CSV | **Keep** (H6b) | Reflected all-column projection incl. `cf_*`; unpaginated, streamed |
| XLS | **Keep** (H6b) | Reproduce the existing SpreadsheetML 2003 shape, per-entity column list |
| vCard | **Keep** (H6b) | `contacts#show`, `leads#show` |
| XML | **Deprecate**, do not port initially | Re-add with `jackson-dataformat-xml` (Low effort) if the log audit finds a consumer |
| Atom / RSS | **Deprecate**, do not port initially | Per-user authenticated feeds; Rome only if a consumer is found |
| HTML / JS | **Out of scope** for the API | Rails keeps serving them (ADR 0002); Phase 8 decides their future |

Deprecation means: while Rails is still live the format keeps working (it is served by Rails,
not the gateway), the endpoints answer with a `Deprecation` and `Sunset` header once routed to
Spring Boot, and the format is removed only after the announced sunset date.

The audit is *pending* on one week of production access logs. Keep rule: non-UI traffic from
more than one distinct user agent, or a named internal consumer. If XML or Atom/RSS pass that
bar, this ADR is amended (not superseded) and the corresponding H6b work is added — both are
rated Low effort precisely so this decision stays cheap to reverse.

## Consequences

- Phase 4 tracks implement JSON only; the contract-diff harness allow-lists XML/Atom/RSS
  requests instead of diffing them.
- Phase 6 H6b keeps its CSV/XLS/vCard scope; Rome and `jackson-dataformat-xml` drop out of the
  dependency list unless the audit reinstates them.
- Error bodies are affected, not just success bodies: `respond_to_not_found`,
  `respond_to_access_denied` and the lead-promotion 422 all have XML variants. Deprecating XML
  means those variants have no Spring Boot equivalent, which the harness must treat as an
  intentional delta.
- CORS is currently `Access-Control-Allow-Origin: *`, so an unknown browser consumer would not
  show up in any allow-list. The gateway should tighten CORS at the same time formats are
  narrowed, otherwise the deprecation is unenforceable.
