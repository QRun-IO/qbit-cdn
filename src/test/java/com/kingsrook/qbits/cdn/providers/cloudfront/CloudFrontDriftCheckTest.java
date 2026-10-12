/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.cdn.providers.cloudfront;


import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudfront.model.CustomOriginConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginProtocolPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Drift comparison against the OpenTofu expected_config output.
 *******************************************************************************/
class CloudFrontDriftCheckTest
{
   private static final String CACHING_DISABLED = CloudFrontDriftCheck.MANAGED_CACHING_DISABLED_POLICY_ID;
   private static final String EXPECTED         = Fixtures.load("cloudfront/expected-config.json");



   /*******************************************************************************
    ** A snapshot that matches expected-config.json.
    *******************************************************************************/
   private static CloudFrontConfigSnapshot matching()
   {
      return snapshot(true, 8443, List.of("X-Origin-Verify"), List.of(
         new CloudFrontConfigSnapshot.Behavior("/_next/static/*", "static-policy", "o"),
         new CloudFrontConfigSnapshot.Behavior("/api/*", CACHING_DISABLED, "o"),
         new CloudFrontConfigSnapshot.Behavior("/v1/me*", CACHING_DISABLED, "o"),
         new CloudFrontConfigSnapshot.Behavior("*", "default-policy", "o")));
   }



   /*******************************************************************************
    ** A snapshot with the given variable facts.
    *******************************************************************************/
   private static CloudFrontConfigSnapshot snapshot(boolean enabled, Integer port, List<String> headers, List<CloudFrontConfigSnapshot.Behavior> behaviors)
   {
      return new CloudFrontConfigSnapshot(enabled, List.of("api.kof22.com", "kof22.com", "s3.kof22.com", "www.kof22.com"), "PriceClass_All", "http2and3",
         "origin.kof22.com", port, "https-only", headers, behaviors);
   }



   /*******************************************************************************
    ** No drift, and an empty expectation checks nothing.
    *******************************************************************************/
   @Test
   void noDrift()
   {
      assertThat(CloudFrontDriftCheck.compare(matching(), EXPECTED)).isEmpty();
      assertThat(CloudFrontDriftCheck.compare(snapshot(false, null, List.of(), List.of()), "{}")).isEmpty();
   }



   /*******************************************************************************
    ** A missing origin header, a changed port and a disabled distribution.
    *******************************************************************************/
   @Test
   void originDrift()
   {
      List<CloudFrontDriftFinding> findings = CloudFrontDriftCheck.compare(snapshot(false, 443, List.of(), matching().behaviors()), EXPECTED);
      assertThat(findings).containsExactly(
         new CloudFrontDriftFinding("enabled", "true", "false"),
         new CloudFrontDriftFinding("origin.port", "8443", "443"),
         new CloudFrontDriftFinding("origin.header:X-Origin-Verify", "present", "(missing)"));

      assertThat(CloudFrontDriftCheck.compare(snapshot(true, null, List.of("x-origin-verify"), matching().behaviors()), EXPECTED))
         .containsExactly(new CloudFrontDriftFinding("origin.port", "8443", "(missing)"));
   }



   /*******************************************************************************
    ** Alias, price class, HTTP version, domain and protocol changes.
    *******************************************************************************/
   @Test
   void distributionDrift()
   {
      CloudFrontConfigSnapshot changed = new CloudFrontConfigSnapshot(true, List.of("kof22.com", "www.kof22.com"), "PriceClass_100", "http2",
         "other.kof22.com", 8443, "match-viewer", List.of("X-Origin-Verify"), matching().behaviors());
      assertThat(CloudFrontDriftCheck.compare(changed, EXPECTED)).extracting(CloudFrontDriftFinding::area)
         .containsExactly("aliases", "origin.domain", "origin.protocol", "price_class", "http_version");
   }



   /*******************************************************************************
    ** A missing behavior, an unexpected one, a reorder, and a never-cache
    ** path that is cached.
    *******************************************************************************/
   @Test
   void behaviorDrift()
   {
      CloudFrontConfigSnapshot changed = snapshot(true, 8443, List.of("X-Origin-Verify"), List.of(
         new CloudFrontConfigSnapshot.Behavior("/v1/me*", "some-caching-policy", "o"),
         new CloudFrontConfigSnapshot.Behavior("/_next/static/*", "static-policy", "o"),
         new CloudFrontConfigSnapshot.Behavior("/preview*", CACHING_DISABLED, "o"),
         new CloudFrontConfigSnapshot.Behavior("*", "default-policy", "o")));

      assertThat(CloudFrontDriftCheck.compare(changed, EXPECTED)).containsExactly(
         new CloudFrontDriftFinding("behavior:/api/*", "present", "(missing)"),
         new CloudFrontDriftFinding("behavior:/preview*", "absent", "present"),
         new CloudFrontDriftFinding("behavior_order", "/_next/static/* > /v1/me*", "/v1/me* > /_next/static/*"),
         new CloudFrontDriftFinding("never_cache:/api/*", CACHING_DISABLED, "(missing)"),
         new CloudFrontDriftFinding("never_cache:/v1/me*", CACHING_DISABLED, "some-caching-policy"));
   }



   /*******************************************************************************
    ** A custom CachingDisabled policy id overrides the managed one.
    *******************************************************************************/
   @Test
   void customCachingDisabledPolicy()
   {
      String expected = "{\"never_cache_paths\": [\"/api/*\"], \"caching_disabled_policy_id\": \"custom-off\"}";
      assertThat(CloudFrontDriftCheck.compare(matching(), expected))
         .containsExactly(new CloudFrontDriftFinding("never_cache:/api/*", "custom-off", CACHING_DISABLED));
   }



   /*******************************************************************************
    ** Invalid expected_config is refused.
    *******************************************************************************/
   @Test
   void invalidExpectedConfig()
   {
      assertThatThrownBy(() -> CloudFrontDriftCheck.compare(matching(), "not json")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> CloudFrontDriftCheck.compare(matching(), null)).isInstanceOf(IllegalArgumentException.class);
   }



   /*******************************************************************************
    ** Snapshot building from sparse configs: no origins, an http-only origin,
    ** and a default behavior pointing at an unknown origin (first one wins).
    *******************************************************************************/
   @Test
   void sparseSnapshots()
   {
      CloudFrontConfigSnapshot empty = CloudFrontConfigSnapshot.from(DistributionConfig.builder().build());
      assertThat(empty.enabled()).isFalse();
      assertThat(empty.aliases()).isEmpty();
      assertThat(empty.behaviors()).isEmpty();
      assertThat(empty.originDomain()).isNull();
      assertThat(empty.originPort()).isNull();

      DistributionConfig httpOnly = DistributionConfig.builder()
         .origins(o -> o.quantity(2).items(
            Origin.builder().id("a").domainName("a.example.com")
               .customOriginConfig(CustomOriginConfig.builder().httpPort(8080).httpsPort(443).originProtocolPolicy(OriginProtocolPolicy.HTTP_ONLY).build()).build(),
            Origin.builder().id("b").domainName("b.example.com").build()))
         .defaultCacheBehavior(d -> d.targetOriginId("missing").cachePolicyId("p"))
         .build();
      CloudFrontConfigSnapshot snapshot = CloudFrontConfigSnapshot.from(httpOnly);
      assertThat(snapshot.originDomain()).isEqualTo("a.example.com");
      assertThat(snapshot.originPort()).isEqualTo(8080);
      assertThat(snapshot.behavior("*")).isPresent();
      assertThat(snapshot.behavior("/nope")).isEmpty();

      DistributionConfig secondOrigin = httpOnly.toBuilder().defaultCacheBehavior(d -> d.targetOriginId("b")).build();
      CloudFrontConfigSnapshot b = CloudFrontConfigSnapshot.from(secondOrigin);
      assertThat(b.originDomain()).isEqualTo("b.example.com");
      assertThat(b.originPort()).isNull();
      assertThat(b.originHeaderNames()).isEmpty();
   }
}
