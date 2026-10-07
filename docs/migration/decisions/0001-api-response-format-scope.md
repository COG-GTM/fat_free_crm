# ADR 0001 — Response-format scope for `/api/v1`

- **Status**: Accepted, pending production-log confirmation (owner: deployment operator)
- **Date**: 2026-10-07
- **Phase**: 0 (AB-262; constrains Phase 4 read API and Phase 6 track H6b)

## Context

`ApplicationController` declares HTML, JavaScript, JSON, XML, Atom, CSV, RSS, and XLS response
formats across inherited controllers, with vCard on two record-show actions. The current-code
audit is in [`../api-format-inventory.md`](../api-format-inventory.md). The inherited declarations
are broader than the dedicated format implementations: they are not evidence that each format
has an external consumer.

Porting a format is not free — each one is a content negotiator, a projection, and a set of
harness assertions in Phase 4/6.

## Decision

For `/api/v1` served by Spring Boot:

| Format | Decision | Notes |
|---|---|---|
| JSON | **Keep** — the only first-class API format | The API contract is `openapi.yaml` (ADR 0005) |
| CSV | **Keep** (H6b) | Reflected all-column projection including `cf_*`; unpaginated |
| XLS | **Keep** (H6b) | Reproduce the existing SpreadsheetML 2003 shape and per-entity column list |
| vCard | **Keep** (H6b) | Existing contact and lead record exports |
| XML | **Deprecate**, pending production-log confirmation | Do not port initially; reconsider if logs identify a consumer |
| Atom / RSS | **Deprecate**, pending production-log confirmation | Do not port initially; reconsider if logs identify a consumer |
| HTML / JS | **Out of scope** for the API | Rails keeps serving these UI formats (ADR 0002) |

Deprecation means: while Rails is still live the format keeps working; endpoints routed to Spring
Boot answer with a `Deprecation` and `Sunset` header, and the format is removed only after the
announced sunset date.

The production access-log decision remains open. Review at least one week of production access
logs. Keep XML or Atom/RSS if there is non-UI traffic from more than one distinct user agent or a
named internal consumer. If either format passes that bar, amend this ADR and add the corresponding
H6b work.

## Consequences

- Phase 4 tracks implement JSON as the only first-class API format; the contract-diff harness
  allow-lists intentional XML/Atom/RSS deprecation deltas.
- Phase 6 H6b keeps CSV/XLS/vCard in scope. Rome and XML serialization dependencies are not
  required unless the log audit reinstates those formats.
- XML error bodies are affected as well as success bodies: Rails emits XML for not-found,
  authorization-denied, and lead-promotion errors. Deprecation therefore changes error responses
  too.
- Rails currently sets `Access-Control-Allow-Origin: *`; an unknown browser consumer may not show
  up in a server-side allow-list. Tighten CORS alongside narrowing API formats.
