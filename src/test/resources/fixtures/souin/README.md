# Souin fixtures

Hand-built from the darkweak/souin sources (`pkg/api/souin.go`, the
invalidation handler) and README, not recorded from a running Souin.

| File | What |
|---|---|
| `invalidate-paths-request.json` | the `POST /souin-api/souin` body the adapter sends for a PATHS batch |

Tag purges are `PURGE /souin-api/souin` with a `Surrogate-Key` header and an
empty body. Souin answers `204 No Content`, so there is no response fixture.
