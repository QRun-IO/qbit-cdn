# Revalidation hook migration (F11 / qbit-cdn #15)

Reference: core source read directly at tag `v4.1.0-RC.5`, including
`WebhookSigner`, `WebhookSecret`, `WebhookVerifier`, `SafeHttpClient`,
`SafeHttpPolicy`, and `AddressRules`. No temporary core bridge is needed.

## Receiver found locally

The plain `Website-Frontend` and `Website-Publisher` checkouts have no match for
`X-Revalidate-Signature`. The implementation is in
`R:/Git.Local/KofTwentyTwo/Website-Frontend-wt-isr`, branch `feature/cdn-isr`,
HEAD `476318b` at inspection (working files inspected):

- `src/lib/revalidate.ts:5`: names `x-revalidate-signature`.
- `src/lib/revalidate.ts:9`: `signBody` returns `sha256=` plus hex HMAC-SHA256
  over the body using the raw string key (the calculation is line 10).
- `src/lib/revalidate.ts:14`: `verifySignature` compares this value in constant
  time; line 21 lowercases the incoming legacy header.
- `src/app/api/revalidate/route.ts:30`: reads `CDN_REVALIDATE_SECRET`.
- `src/app/api/revalidate/route.ts:36`: reads the body before JSON parsing.
- `src/app/api/revalidate/route.ts:40`: reads only the legacy signature header
  and calls the verifier; standard requests currently return 401.
- `src/__tests__/revalidate-route.test.ts:12`: test requests send only the legacy header.

These receiver files were inspected, not modified.

## Deployment and receiver changes

1. Add the three hook columns documented in README. Existing rows (null
   `signatureFormat`) keep the LEGACY signature; switch a hook to `STANDARD_WEBHOOKS`
   after its receiver verifies Standard Webhooks. New rows default to `STANDARD_WEBHOOKS`.
   For internal HTTP endpoints also explicitly allow HTTP and the intended host.
2. Update `src/lib/revalidate.ts:14` to verify Standard Webhooks: require all
   three headers, validate an integer Unix timestamp within 300 seconds of the
   receiver clock (reject malformed/non-finite values), then HMAC-SHA256 the
   bytes `<webhook-id>.<webhook-timestamp>.<raw body>` and compare decoded
   signatures in constant time. Accept any matching `v1,<base64>` entry in the
   space-separated signature list. Do not lowercase base64 signatures.
   Match core secret parsing: trim text; decode `whsec_` base64 keys, otherwise
   UTF-8; use at least 16 key bytes. Rotate short existing keys on both sides.
3. At `src/app/api/revalidate/route.ts:36`, retain raw body bytes for verification;
   at line 40 pass all three headers to the new verifier before parsing JSON.
   Update its header import/documentation (lines 5 and 22). During migration the
   receiver may explicitly support both formats; do not fall back to legacy
   when standard headers are present but invalid.
4. Update `src/__tests__/revalidate-route.test.ts:12` and helper tests to cover
   valid standard requests, altered body/id/timestamp, missing headers, stale
   or future timestamps, malformed timestamps, encoded keys, and legacy mode.
5. Deploy the receiver, then switch its hook to `STANDARD_WEBHOOKS`.

The JSON payload remains `{"tags":[...]}`. One UUID identifies a hook invocation,
shared across its fan-out requests; each request gets its own current timestamp.
A later dispatcher invocation generates a new UUID. Revalidation remains
idempotent, so receivers must not rely on this ID to deduplicate purge retries;
any optional deduplication must remain local to each pod, not suppress other pods.

## Compatibility and verification

The legacy HMAC helper remains solely for the explicitly supported legacy format,
not as a temporary replacement for core. Golden tests use fixed digests calculated
independently of that helper, including a short key and a whitespace/prefix key.
Standard requests are checked using RC.5 `WebhookVerifier`; transport tests cover
HTTP/private-address refusal, explicit local exceptions and mixed-address fan-out.

The test/collaborator constructor now takes `(secrets, resolver)`; callers no longer
inject `java.net.http.HttpClient`. The production no-argument constructor remains.
The only existing test adaptation outside the runner tests is `PurgePipelineTest`
using this constructor. The RC.5 bump required no unrelated code fixes.
