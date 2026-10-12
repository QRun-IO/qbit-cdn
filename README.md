# qbit-cdn

QQQ qbit that puts any CDN in front of a QQQ application: portable cache
headers, change-driven invalidation (tags, or planned paths for CDNs without
tag purge) and edge statistics. Design: QRun-IO/qqq
`docs/specs/2026-10-12-qbit-cdn-design.md`.

Status: Phase P1 in progress. This first slice is the provider-neutral core:

- `spi`: `CdnProviderInterface` and its value types (capabilities, header
  dialect, purge batches and results, provider config with a secret
  reference, never a secret).
- `tags`: canonical cache tags (`post:123`, `blog:7:list`), the portable tag
  budget, and tag/path templates rendered from record values.
- `planning`: `PathInvalidationPlanner`, which turns tags' paths into
  CloudFront-style invalidations within a monthly path budget (hubs skipped,
  covered paths removed, siblings collapsed to wildcards, coarsening, pay or
  defer above the hard limit, batching).
- `providers`: the `NONE` provider (headers only, purges recorded as skipped).

Next: QQQ tables and processes, ESB trigger and dispatcher, response-header
policy, CloudFront and Souin adapters, Admin Health widget.

```sh
./mvnw verify
```
