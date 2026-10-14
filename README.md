# qbit-cdn

QQQ qbit that puts any CDN in front of a QQQ application: portable cache
headers, change-driven invalidation, edge statistics and an Admin -> CDN app.
Design: QRun-IO/qqq `docs/specs/2026-10-12-qbit-cdn-design.md`.

## What it does

- **Tables** (standard QQQ tables, `cdn_` prefix): `cdn_provider`, `cdn_hostname`,
  `cdn_cache_policy`, `cdn_table_tag_rule`, `cdn_purge_request`,
  `cdn_revalidation_hook`, `cdn_edge_stat_daily`, `cdn_origin_check`,
  `cdn_drift_finding`. Secrets never live in tables (`secretRef` names an
  environment variable prefix).
- **Purge pipeline:** a record-change listener on the configured purgeable tables
  turns changes into canonical tags and paths (`cdn_table_tag_rule`), records
  `cdn_purge_request` rows, and the scheduled dispatcher sends them: flush
  window, merge, revalidation hooks first (HMAC-signed, fan-out to every pod),
  tag purge or budgeted path invalidation (CloudFront), async polling, retries
  with backoff, then DEAD.
- **Header policy:** `CdnResponseHeaderPolicy` chooses `Cache-Control`,
  `CDN-Cache-Control` and tag headers from `cdn_cache_policy` (always
  `private, no-store` for authenticated or cookie-setting responses).
- **Actions** (standard streamed ETL: Review is the dry run, Result has linked
  lines; never in the nav): Purge From CDN (per purgeable table), Retry Purge,
  Purge Everything, Test Connection, Import Edge Stats, Check Config Drift;
  scheduled: dispatcher, daily stats import, daily drift check.
- **Admin -> CDN app:** sections Connection / Settings / Activity / Performance,
  a home widget with honest empty states.
- **Providers:** CloudFront (invalidations, connection test, CloudWatch stats,
  drift against the OpenTofu `expected_config`), Souin (self-hosted, tags),
  NONE. See `docs/cloudfront-expected-config.md`.
- **Permissions:** `cdn.view`, `cdn.purge`, `cdn.purgeAll`, `cdn.admin`.

## Health checks

Central System Health registers `cdn.provider.<id>` for each enabled
`cdn_provider` row: **DEPENDENCY**, component `cdn`, category **CDN**, every
**900 seconds**, with a **20000 ms** timeout. It reuses the read-only connection
test: failure is DOWN; success without the origin header is DEGRADED; otherwise
UP. Providers without connection-test support report UNKNOWN. Disable a check
centrally with `health_check.enabled`.

Details contain only provider type, a known provider status (or UNKNOWN), and
whether the origin header is configured. The manual `cdnTestConnection` action
and its `lastHealth*` fields remain; the daily scheduled step checks drift only.
The host must use a QQQ build containing the central health SPI (currently the
locally installed `4.1.0-SNAPSHOT`).

## Tag rules

`cdn_table_tag_rule.onlyWhenFieldsChange` is a comma-separated list of QQQ field
names. Plain entries (for example `title,slug`) purge only when at least one
listed field changes. Prefix a field with `!` to ignore it: `!viewCount` prevents
a view-count-only update from purging, while a title change still purges.
With `!viewCount, !lastIndexedAt`, any change outside those fields counts.
Mixed lists keep the allow-list and exclude ignored fields, even if also listed
plainly: `title,viewCount,!viewCount` counts only title changes.

Names are trimmed and case-sensitive. Partial updates retain omitted old values;
only unequal old and new values count. This filter does not restrict inserts or
deletes. The separate `publishedField` filter still applies to all changes.

### Purge-path placeholders

`cdn_table_tag_rule` templates (`tagTemplates`, `invalidationPaths`) can use `{field}` for the changed record's own fields and `{prefix.field}` for a joined record, e.g. `/blog/{blog.slug}/{slug}*`.

A joined placeholder is resolved like this:

1. The foreign-key field is `<prefix>Id` on the rule's table (`blogId` for `{blog.slug}`).
2. The joined table is the one behind that field's possible-value source: the source's table name for a table-type source, or the source name when it names a table. So `{blog.slug}` reads `cms_blog` when `blogId` has a possible-value source on `cms_blog`.
3. With no such field or possible-value source, the bare prefix is the table name (`blog`).

If the joined table does not exist, the placeholder is skipped (the rest of the template is dropped, as for any missing value) and a warning is logged once per table per rule evaluation.

## Install

```java
new CdnQBitProducer().withQBitConfig(new CdnQBitConfig()
   .withBackendName("rdbms")
   .withSchedulerName("quartz")
   .withPurgeableTables(List.of("blog_post", "page", "project")))
   .produce(qInstance);
```

The `cdn_` tables must exist in the backend (see the app's migrations).
CloudFront credentials: `<secretRef>_ACCESS_KEY_ID` / `<secretRef>_SECRET_ACCESS_KEY`.
Drift: the OpenTofu `expected_config` JSON in `CDN_EXPECTED_CONFIG` (or the
variable named by the provider setting `expectedConfigEnv`).

## Revalidation hooks

Requires QQQ **4.1.0-RC.5** (the default `qqq-snapshot` profile imports the released BOM;
its historical property name does not select a core SNAPSHOT).

New `cdn_revalidation_hook` rows default `signatureFormat` to `STANDARD_WEBHOOKS`;
existing rows with a null format keep the `LEGACY` signature. Signed requests use core `WebhookSigner` and
`WebhookSecret`: `webhook-id`, `webhook-timestamp`, and `webhook-signature`.
Rows on `LEGACY` (or null) keep sending
`X-Revalidate-Signature: sha256=<lowercase hex HMAC-SHA256 of the body>`.
Legacy keys remain the exact UTF-8 environment value, with no trimming or decoding.
Standard keys must contain at least 16 bytes after core parsing (trimmed raw text,
or base64-decoded `whsec_` text). `secretRef` names the environment variable;
as before, no resolved secret means no signature headers.

Core `SafeHttpClient` now enforces HTTPS and rejects internal addresses by default.
For an internal HTTP pod endpoint explicitly set `allowPlainHttp=true` and
`internalAllowlist` to its exact hostname, a `.suffix`, IP, or CIDR (comma/whitespace
separated). Metadata and unspecified addresses remain blocked. Every DNS address
is checked before fan-out; the original hostname is retained for Host/TLS, and
connections use the checked addresses without resolving again. Redirects fail,
responses are bounded to 64 KiB, and `timeoutMs` is a positive per-request total
limit (default 5000). These transport rules apply to both signature formats.

Host apps must migrate `cdn_revalidation_hook` to add `signatureFormat` (string),
`allowPlainHttp` (boolean), and `internalAllowlist` (text). Backfill `LEGACY` for
old receivers; configure internal network exceptions explicitly before dispatch.
See [receiver migration](docs/revalidation-hook-migration.md) for the inspected
KoF22 receiver and exact changes required.

## Build

```sh
./mvnw verify   # tests, coverage gate, checkstyle
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

### Before a CDN is live

With no enabled CDN provider (including providers of type `NONE`), purges are
recorded only, not sent. A real provider also needs a `LIVE` or `LOCKED` hostname
with purging enabled. Purge From CDN, Purge Everything, and Retry Purge show a
Review warning and a recorded-only Result when sending is unavailable. Requests
for enabled providers remain visible with `SKIPPED_NONE_PROVIDER` or
`SKIPPED_STAGE_OFF`; with no enabled provider, no new request row is created.
The home Connection card explains the unconfigured state, and purge
activity labels recorded-only requests when disconnected.

This version does not run origin header checks. The Origin headers card says
nothing has been checked until the host application writes results to the
standard `cdn_origin_check` table; those results continue to appear normally.
