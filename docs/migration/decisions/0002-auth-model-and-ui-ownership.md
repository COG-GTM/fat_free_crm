# ADR 0002 — Auth model and who owns the UI after cutover

- **Status**: Accepted for API auth; UI-past-cutover sub-decision pending product sign-off (owner: product)
- **Date**: 2026-10-07
- **Phase**: 0 (AB-262; constrains Phase 1 track C1, Phase 7, Phase 8)

## Context

Today authentication is Devise over Rails cookie sessions. Authorization is CanCanCan over the
`access` column and `permissions` rows.

The migration plan runs Rails and Spring Boot side by side against one database, so during the
strangler window a request may be served by either app. Two decisions must be kept distinct:

1. How does the `/api/v1` API authenticate?
2. Does the Rails UI survive past backend cutover?

The second is a separate, explicitly open product decision. It must not block the API-auth choice.

## Decision

**Use stateless JWT access and refresh tokens for `/api/v1`, served by Spring Boot. Rails keeps its
Devise cookie sessions and serves the UI.**

- Spring Boot issues and validates JWT access and refresh tokens for `/api/v1`; API requests do
  not reach into Rails session storage.
- Rails keeps its existing Devise cookie-session behavior for the UI. The two authentication
  paths coexist and do not consume each other's credentials.
- **Whether the Rails UI survives past cutover is pending product sign-off.** This is an
  independent, explicitly open sub-decision owned by product, not a prerequisite to the accepted
  API-auth decision.

### Error-contract consequence

Spring Boot authorization denial returns **403**, whereas Rails currently returns **401** for the
corresponding JSON denial. This intentional difference must be allow-listed in the contract-diff
harness. Spring Boot errors use RFC 9457 `application/problem+json`.

## Consequences

- Phase 1 C1 implements API access/refresh token issuance and validation. Rails' Devise cookie
  sessions remain unchanged.
- The Rails-UI-past-cutover question remains open until product signs off; no implementation choice
  in this ADR settles it.
- Rails session-stored view state remains a UI concern and is not coupled to Spring API token
  handling.
- Registration, confirmation, and password-reset flows remain in Devise during the strangler
  window unless a later decision changes ownership.
