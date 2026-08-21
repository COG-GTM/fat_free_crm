# ADR 0002 — Auth model and who owns the UI after cutover

- **Status**: **Proposed — needs product sign-off** (owner: product)
- **Date**: 2026-08-21
- **Phase**: 0 (constrains Phase 1 track C1, Phase 7, Phase 8)

## Context

Today authentication is Devise (`database_authenticatable`, `recoverable`, `rememberable`,
`trackable`, `confirmable`, plus `devise-encryptable` with a legacy SHA-512 scheme) over Rails
session cookies. Authorization is CanCanCan over the `access` column and `permissions` rows.

The migration plan runs Rails and Spring Boot side by side against one database, so during the
strangler window a request may be served by either app. Two questions have to be answered
together, because the answer to the second one determines the cheapest answer to the first:

1. Does the API authenticate with JWTs or with the existing session cookies?
2. Does the Rails UI survive cutover, or is it replaced (Phase 8: 354 view files, rated Very
   High effort and explicitly optional)?

## Decision (proposed)

**Two-stage auth, and Rails keeps the UI.**

- **Stage 1 (Phases 1–6, the strangler window): shared session cookies.** Spring Boot reads the
  same Rails session cookie and resolves it to a user via a shared session store (Rails switches
  to an ActiveRecord/Redis session store; Spring Boot's filter reads it). No client changes, and
  the browser UI can call Spring Boot endpoints through the gateway on the same origin without a
  token-refresh story.
- **Stage 2 (Phase 7): JWT for programmatic clients**, issued by a Spring Boot login endpoint,
  as an *addition* to the cookie path. Cookies remain for the Rails-served UI.
- **The Rails UI survives** the backend migration. Phase 8 stays out of the critical path and is
  a separate product decision.
- **Legacy password hashes must be reproducible in Java regardless** — this is unchanged by the
  choice above, and stays Phase 1 (C1) work, because the fallback (a forced-reset campaign) has
  product lead time.

## Alternative considered — JWT-only from Phase 1

Cleanest end state, and what the plan's Phase 7 assumed. Rejected as the *default* because,
while the Rails UI is the only real client, it forces either a token bridge in Rails or moving
authentication ahead of the resources that need it — front-loading the highest-risk cutover into
the phase with the least test coverage. It becomes the right answer immediately if product
decides to build an SPA (Phase 8), since the SPA needs tokens anyway.

## Consequences

- Phase 1 C1 scope: legacy SHA-512 encoder + `UserDetailsService` + `trackable` updates, plus a
  session-cookie authentication filter — the token endpoint moves to Phase 7.
- Rails must move off the cookie-store session (it currently keeps filters, sort prefs, and view
  toggles in the session) to a shared server-side store. This overlaps with plan rule 4
  (session state → explicit query params + `UserPreference`), so it is not extra work — but it
  must land in Phase 1, before any endpoint is routed to Spring Boot.
- Registration / confirmation / password-reset stay in Devise until Phase 7; Spring Boot never
  writes those flows during the strangler window, avoiding dual-write on `users`.
- If product picks an SPA, this ADR is superseded and Phase 1 gains the token endpoint.

## What sign-off is needed on

1. Rails UI stays as the front end after backend cutover (yes/no).
2. If no: is a Phase 8 SPA funded, which flips this ADR to JWT-only?
3. Acceptance of a forced password-reset campaign as the fallback if the legacy hash scheme
   cannot be reproduced in Java (Phase 1 will answer whether it is needed).
