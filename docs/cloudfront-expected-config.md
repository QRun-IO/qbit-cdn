# CloudFront `expected_config` (drift check input)

The OpenTofu root `terraform/cdn-cloudfront/` exports an output named
`expected_config`: a JSON object describing what the distribution should look
like. The qbit's "Check config drift" action reads the live distribution with
`GetDistributionConfig` (`CloudFrontCdnProvider.readConfig`) and compares it
with this JSON (`CloudFrontDriftCheck.compare`). Each difference becomes a
`CloudFrontDriftFinding(area, expected, actual)`.

**Every key is optional. A key that is absent is not checked.** Unknown keys
are ignored, so tofu can add informational fields such as `distribution_id`.

## Schema

| Key | Type | Compared with | Finding `area` |
|---|---|---|---|
| `enabled` | boolean | `DistributionConfig.Enabled` | `enabled` |
| `aliases` | string[] | alternate domain names, compared as a sorted set | `aliases` |
| `origin_domain` | string | domain name of the default behavior's origin | `origin.domain` |
| `origin_port` | number | that origin's HTTPS port (HTTP port when `http-only`) | `origin.port` |
| `origin_protocol_policy` | string | `https-only`, `http-only` or `match-viewer` | `origin.protocol` |
| `origin_header_names` | string[] | each name must be an origin custom header (case-insensitive). **Names only: header values are secrets and are never read or exported.** | `origin.header:<name>` |
| `behavior_paths` | string[] | ordered path patterns of the non-default cache behaviors. Missing, unexpected and reordered behaviors are all findings, because CloudFront matches behaviors in order. | `behavior:<path>`, `behavior_order` |
| `never_cache_paths` | string[] | each path must be a behavior whose cache policy is `caching_disabled_policy_id` | `never_cache:<path>` |
| `caching_disabled_policy_id` | string | the policy id required by `never_cache_paths`. Default: the AWS managed `CachingDisabled` policy, `4135ea2d-6df8-44a3-9df3-4b5a84be39ad`. | (none) |
| `price_class` | string | `PriceClass` | `price_class` |
| `http_version` | string | `HttpVersion` (`http2`, `http2and3`, ...) | `http_version` |
| `distribution_id` | string | not compared (informational) | (none) |

In findings, `actual` is `(missing)` when the live config has no such value.
For `origin.header:*` and `behavior:*` findings, `expected`/`actual` are
`present` / `absent` / `(missing)`.

## Example (kof22)

```json
{
   "distribution_id": "E2EXAMPLE123",
   "enabled": true,
   "aliases": ["kof22.com", "www.kof22.com", "api.kof22.com", "s3.kof22.com"],
   "origin_domain": "origin.kof22.com",
   "origin_port": 8443,
   "origin_protocol_policy": "https-only",
   "origin_header_names": ["X-Origin-Verify"],
   "behavior_paths": ["/_next/static/*", "/_next/image*", "/kof22-website-files/*", "/api/*", "/preview*", "/v1/me*", "/v1/contact*", "/oauth/*"],
   "never_cache_paths": ["/api/*", "/preview*", "/v1/me*", "/v1/contact*", "/oauth/*"],
   "price_class": "PriceClass_All",
   "http_version": "http2and3"
}
```

A sketch of the tofu output:

```hcl
output "expected_config" {
  value = jsonencode({
    distribution_id        = aws_cloudfront_distribution.kof22.id
    enabled                = aws_cloudfront_distribution.kof22.enabled
    aliases                = sort(tolist(aws_cloudfront_distribution.kof22.aliases))
    origin_domain          = "origin.kof22.com"
    origin_port            = 8443
    origin_protocol_policy = "https-only"
    origin_header_names    = ["X-Origin-Verify"]
    behavior_paths         = [for b in aws_cloudfront_distribution.kof22.ordered_cache_behavior : b.path_pattern]
    never_cache_paths      = local.never_cache_paths
    price_class            = aws_cloudfront_distribution.kof22.price_class
    http_version           = aws_cloudfront_distribution.kof22.http_version
  })
}
```

The output must never include the origin header **value**.
