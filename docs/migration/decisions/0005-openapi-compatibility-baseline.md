# ADR 0005 — `openapi.yaml` is the frozen compatibility baseline

- **Status**: Accepted
- **Date**: 2026-08-21
- **Phase**: 0 (constrains Phase 2 track A2 and every ported endpoint in Phases 4–5)

## Context

The migration's parallelism depends on being able to prove a ported endpoint behaves like the
Rails one. That requires a fixed description of "the Rails one": a moving target would let
parallel tracks drift on envelopes, error shapes, and status codes.

`docs/migration/openapi.yaml` (1971 lines) describes the *current* Rails JSON/XML behaviour — it
is a description, not a design. It must not be edited to describe the Spring Boot target.

## Decision

- The Rails commit that `openapi.yaml` describes is tagged **`openapi-baseline-v1`**
  → `b5a46dcc1fb9cd14512640123d27d91cc221de52`
  (`docs/migration/openapi.yaml`, sha256 `eff48b0ec3e0de4ac31d7b13240bdaf1e1109f583ba52295ec18f026d647437e`).
- `openapi.yaml` is **frozen**. Corrections are allowed only when the spec is found to
  *misdescribe* Rails; every such change is a commit that says which endpoint was wrong and how
  it was verified against the tag.
- The **target** contract lives in a separate document per Phase 4/5 track (the OpenAPI slice
  that a track's first commit adds, per plan rule 2). Intentional differences from the baseline
  — e.g. 401 vs. 403, XML deprecation (ADR 0001) — are recorded in the harness allow-list, not
  by editing the baseline.
- The Phase 2 A2 harness diffs live Rails at the tag against Spring Boot; the tag is what CI
  checks out to build the Rails side of the comparison.

## Consequences

- Rails may keep changing on `main` during the migration; the harness pins the tag, so behaviour
  drift in Rails shows up as a harness failure rather than silently redefining the contract. Any
  deliberate Rails behaviour change during the migration needs a new baseline tag
  (`openapi-baseline-v2`) and a re-diff of already-ported endpoints.
- Endpoints absent from the baseline (the `new`/`edit`/`options` JS actions replaced by
  `metadata/{entity}` in Phase 4 E4e) have no diff target; those are specified in the track's own
  slice and reviewed by hand.
- Contract equivalence is only as good as the seeded fixture corpus both apps share (Phase 2).
  The corpus must include shared/private/public access rows, tagged records, and custom fields,
  or "diff-clean" understates risk.
