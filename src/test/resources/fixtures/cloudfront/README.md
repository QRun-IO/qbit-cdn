# CloudFront fixtures

Hand-built from the AWS CloudFront API reference (REST-XML, API version
`2020-05-31`), not recorded from a live account. Ids, account numbers and the
origin header value are made up. `FIXTURE-SECRET-NEVER-LEAK` is the
`X-Origin-Verify` value in `distribution-config.xml`; tests assert it never
appears in adapter output.

| File | API call |
|---|---|
| `distribution-config.xml` | `GetDistributionConfig` body |
| `distribution.xml` | `GetDistribution` body (same config, plus domain name and status) |
| `invalidation-in-progress.xml`, `invalidation-completed.xml` | `CreateInvalidation` / `GetInvalidation` bodies |
| `invalidation-list.xml` | `ListInvalidations` body (`MaxItems=1`) |
| `error-*.xml` | CloudFront `ErrorResponse` bodies for each mapped error code |
| `expected-config.json` | an OpenTofu `expected_config` output matching `distribution-config.xml` (schema: `docs/cloudfront-expected-config.md`) |

CloudWatch `GetMetricData` uses the smithy RPC v2 CBOR protocol in SDK
2.41, so its tests mock `CloudWatchClient` with Mockito instead of using
fixtures here.
