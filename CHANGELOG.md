# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog; versions follow semantic versioning.

## [0.1.1] - Unreleased

### Added
- Origin header check: Check Origin Headers on Hostnames (and a nightly run over hostnames that are not Off) asks the origin for sample pages and writes `cdn_origin_check` rows. The table had no writer before.
- Guided empty states: help bundle `help/qbit-cdn.yaml` (app and all nine tables, with "How rows get created") applied by `CdnHelp`, and a Setup card on the CDN home.
- "Create default provider" (No CDN), "Create default policies" (two turned-off examples) and "Create default rules" (a tags-only rule per purgeable table) table actions; all insert-only.
- `hostnameRole` is documented as a label that changes nothing; cache policies are documented as applying only where the application calls `CdnResponseHeaderPolicy`.

### Changed
- Require released QQQ BOM 4.1.0-RC.5; revalidation hooks default to core Standard Webhooks signing. Per-hook `signatureFormat=LEGACY` preserves the prior header and signature bytes.
- Use core `SafeHttpClient` for revalidation requests: HTTPS/public targets by default, explicit `allowPlainHttp`/`internalAllowlist` exceptions, bounded responses and checked DNS pinning. Fan-out preserves Host/TLS identity. Host schema migration and receiver migration are described in README and `docs/revalidation-hook-migration.md`.


### Fixed
- Tag rules accept ignored fields in `onlyWhenFieldsChange`, such as `!viewCount`, so counter-only updates do not purge rendered pages. Plain allow-lists still work, and ignored fields take precedence in mixed lists. No schema change is needed.
- Purge actions warn when providers are NONE or hostnames are OFF, and report requests as recorded but not sent. The home explains recorded-only purges and that this version does not run origin header checks.
- Tag rule `{prefix.field}` placeholders now resolve the joined table from the `<prefix>Id` field's possible-value source, so prefixed tables (`cms_blog`) work; a missing joined table is logged instead of silently skipped.

## [0.1.0] - Unreleased

### Added
- First implementation; see README.md for the tables, permissions and configuration.
