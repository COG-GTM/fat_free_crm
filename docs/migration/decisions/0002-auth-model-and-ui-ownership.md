# ADR 0002 — Auth model and who owns the UI after cutover

- **Status**: **Accepted** for the API auth mechanism (JWT); the UI-ownership half still needs
  product sign-off (owner: product)
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

## Decision

**JWT for `/api/v1` from Phase 1; no shared session store; UI ownership stays open.**

- **`/api/v1` authenticates with a stateless bearer token (JWT)** issued by a Spring Boot login
  endpoint, starting in Phase 1. The gateway routes by path prefix, so the Spring app must be
  able to authenticate a request without reaching into Rails' session storage; a shared session
  store would couple the two apps' deployment lifecycles in exactly the phase where we want them
  independent, and it puts a Rails session-store migration on the Phase 1 critical path.
- **Rails keeps its own cookie sessions untouched** for the HTML UI it continues to serve. The
  two auth paths coexist; neither reads the other's credential.
- **Whether the Rails UI survives cutover stays open**, and it is a Phase 7 question, not a
  Phase 1 one: if the Rails UI must call Spring endpoints directly, the cheapest bridge is a
  Spring-issued cookie (or a session store shared at that point) rather than teaching 354 Haml
  views to carry a bearer token. Phase 1 keeps token minting isolated in `JwtService`, behind
  `AuthController`, so either outcome reuses the authentication path underneath it.

### Superseded proposal

An earlier draft of this ADR proposed shared session cookies for Phases 1–6 with JWT deferred to
Phase 7. It was reversed before Phase 1 landed: it required Rails to move off the cookie-store
session *before* any endpoint could be routed to Spring Boot, which is a change to the live app's
session handling in the phase with the least coverage — a larger risk than the token story it was
avoiding. The implemented Phase 1 foundation follows this ADR as written.

## Independent of either choice

**Legacy password hashes must be reproducible in Java**, and that is Phase 1 (C1) work either
way, because the fallback (a forced-reset campaign) has product lead time. Phase 1 has since
verified it against hashes produced by the Rails app: `devise-encryptable`'s `authlogic_sha512`
(`digest = [password, salt].join('')`, then `stretches` iterations of `Digest::SHA512.hexdigest`,
pepper ignored) reproduces byte-for-byte in Java at `stretches = 20`.

## Cost of reversal

Moderate but bounded, in the direction that matters: adding a cookie/session bridge later is
additive (a second authentication filter resolving a Rails session to the same `UserDetails`),
whereas unwinding a shared session store after Rails had been migrated onto it would not be.

## Consequences

- Phase 1 C1 scope: legacy SHA-512 encoder + `UserDetailsService` + `trackable` updates + JWT
  login/refresh endpoints. No session-cookie filter, and no change to Rails' session handling.
- Rails' session-stored view state (filters, sort prefs, view toggles) is still migrated to
  explicit query params + `UserPreference` per plan rule 4 — but as part of the endpoints that
  need it in Phases 4–5, not as a Phase 1 prerequisite.
- Registration / confirmation / password-reset stay in Devise until Phase 7; Spring Boot never
  writes those flows during the strangler window, avoiding dual-write on `users`.
- Deleted (`deleted_at`) and suspended (`suspended_at`) users must fail authentication with the
  same semantics Rails applies, and successful logins must keep the trackable columns moving so
  the Rails UI's "last seen" data does not go stale while both apps serve traffic.
- `users.authentication_token` is **not** revived as an API credential. Nothing reads it today
  (the `authentication_credentials` param that `links_to_export` puts on feed URLs is never
  consumed server-side), and reusing it would create a second, weaker credential with no
  revocation story.

## What sign-off is still needed on

1. Rails UI stays as the front end after backend cutover (yes/no) — a Phase 7 input, coupled to
   the Phase 8 view-layer decision.
2. Acceptance of a forced password-reset campaign as the fallback if the legacy hash scheme
   cannot be reproduced in Java. Phase 1 has since answered this: the scheme **is** reproducible
   byte-for-byte, so the fallback is not needed.
