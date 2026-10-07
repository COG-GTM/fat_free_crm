# ADR 0005 — `openapi.yaml` is the frozen compatibility baseline

- **Status**: Accepted
- **Date**: 2026-10-07
- **Phase**: 0 (AB-262; constrains Phase 2 benchmark AB-267 and every ported endpoint in Phases 4–5)

## Context

The migration's parallelism depends on proving that a ported endpoint behaves like Rails. That
requires a fixed description of "the Rails one": a moving target would let parallel tracks drift
in envelopes, error shapes, and status codes.

`docs/migration/openapi.yaml` describes the current Rails JSON/XML behavior; it is a description,
not a design. It must not be edited to describe the Spring Boot target.

## Verified baseline facts

- The existing origin tag **`openapi-baseline-v1`** points to
  **`b5a46dcc1fb9cd14512640123d27d91cc221de52`**, the merge commit titled
  `Merge pull request #1 …gem-mapping-doc` (2026-08-20). This ticket does not create or move tags.
- `docs/migration/openapi.yaml` is 1,971 lines, blob
  **`0e27667940f2583b73da7b26b8813a5735ab4edf`**, SHA-256
  **`eff48b0ec3e0de4ac31d7b13240bdaf1e1109f583ba52295ec18f026d647437e`**.
- The last content change to the OpenAPI file is commit
  **`c71347045469ca0f67eb7886933c88c283b61598`**, titled
  `docs: add Spring Boot migration target architecture and OpenAPI 3.1 contract`
  (2026-08-21 01:17 UTC). It is an ancestor of the tag commit.
- The tag is therefore not literally the OpenAPI file's last-change commit. It is the master merge
  commit at which the exact content is frozen: the OpenAPI blob is identical at `c7134704`, the
  tag commit `b5a46dc`, and the epic base `bdb6f202`. The Rails code described by the spec is also
  unchanged between the tag and the epic base.

These facts were checked with:

```bash
git show-ref --tags openapi-baseline-v1
git rev-parse openapi-baseline-v1
git rev-parse c71347045469ca0f67eb7886933c88c283b61598:docs/migration/openapi.yaml
git rev-parse b5a46dcc1fb9cd14512640123d27d91cc221de52:docs/migration/openapi.yaml
git rev-parse bdb6f202009d5484f8cfac7dbd506007fa866360:docs/migration/openapi.yaml
git show b5a46dcc1fb9cd14512640123d27d91cc221de52:docs/migration/openapi.yaml | wc -l
git show b5a46dcc1fb9cd14512640123d27d91cc221de52:docs/migration/openapi.yaml | sha256sum
git merge-base --is-ancestor c71347045469ca0f67eb7886933c88c283b61598 b5a46dcc1fb9cd14512640123d27d91cc221de52
git diff c71347045469ca0f67eb7886933c88c283b61598 b5a46dcc1fb9cd14512640123d27d91cc221de52 -- docs/migration/openapi.yaml
git diff c71347045469ca0f67eb7886933c88c283b61598 b5a46dcc1fb9cd14512640123d27d91cc221de52 -- . ':!docs'
git diff b5a46dcc1fb9cd14512640123d27d91cc221de52 bdb6f202009d5484f8cfac7dbd506007fa866360 -- app config lib db
```

## Decision

- Freeze `docs/migration/openapi.yaml` at tag `openapi-baseline-v1` →
  `b5a46dcc1fb9cd14512640123d27d91cc221de52`.
- Corrections are allowed only when the spec is found to **misdescribe** Rails. Each correction
  must say which endpoint was wrong and how it was verified against the tag.
- The Spring Boot target contract lives separately, in each Phase 4/5 track's OpenAPI slice.
  Intentional target differences—including 401 versus 403 and XML deprecation—go in the
  contract-diff harness allow-list, never in the frozen baseline.
- The Phase 2 AB-267 benchmark harness pins the Rails baseline tag when it needs Rails behavior.

## Consequences

- Rails may continue changing on the migration branch; the harness pins this tag, so behavior drift
  is visible instead of silently redefining the baseline.
- A deliberate Rails behavior change during the migration requires a new tag
  `openapi-baseline-v2` and a re-diff of already-ported endpoints.
- Endpoints absent from the baseline have no compatibility diff target; their target behavior is
  specified in the track's own contract and reviewed separately.
- Contract equivalence depends on a shared seeded fixture corpus that includes shared/private/
  public access rows, tagged records, and custom fields.
