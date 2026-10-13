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
  a home widget with honest empty states, and a Health status card.
- **Providers:** CloudFront (invalidations, connection test, CloudWatch stats,
  drift against the OpenTofu `expected_config`), Souin (self-hosted, tags),
  NONE. See `docs/cloudfront-expected-config.md`.
- **Permissions:** `cdn.view`, `cdn.purge`, `cdn.purgeAll`, `cdn.admin`.

## Purge-path placeholders

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

## Build

```sh
./mvnw verify   # tests, coverage gate, checkstyle
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
