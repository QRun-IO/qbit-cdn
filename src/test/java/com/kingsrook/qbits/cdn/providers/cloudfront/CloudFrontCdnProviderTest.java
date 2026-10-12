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


import java.net.ServerSocket;
import java.net.URI;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeKind;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Contract tests for the CloudFront adapter against an HTTP stub serving the
 ** hand-made fixtures in src/test/resources/fixtures/cloudfront.
 *******************************************************************************/
class CloudFrontCdnProviderTest
{
   private static final String DISTRIBUTION = "E2EXAMPLE123";
   private static final String BASE         = "/2020-05-31/distribution/" + DISTRIBUTION;
   private static final String SECRET       = "FIXTURE-SECRET-NEVER-LEAK";

   private StubHttpServer stub;



   /*******************************************************************************
    ** Fresh stub per test.
    *******************************************************************************/
   @BeforeEach
   void startStub() throws Exception
   {
      stub = new StubHttpServer();
   }



   /*******************************************************************************
    ** Stop the stub.
    *******************************************************************************/
   @AfterEach
   void stopStub()
   {
      stub.close();
   }



   /*******************************************************************************
    ** An adapter pointed at the stub with static test credentials.
    *******************************************************************************/
   private CloudFrontCdnProvider provider()
   {
      return new CloudFrontCdnProvider(new CloudFrontClientFactory(URI.create(stub.baseUrl()),
         StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDTEST", "test-secret"))));
   }



   /*******************************************************************************
    ** A provider row for the fixture distribution.
    *******************************************************************************/
   private static CdnProviderConfig config(Map<String, String> settings)
   {
      return new CdnProviderConfig("kof22-cloudfront", CdnProviderType.CLOUDFRONT, DISTRIBUTION, "CDN_AWS", settings);
   }



   /*******************************************************************************
    ** Stub an XML fixture answer.
    *******************************************************************************/
   private void xml(String method, String path, int status, String fixture)
   {
      stub.stub(method, path, new StubHttpServer.Stub(status, Fixtures.load("cloudfront/" + fixture), Map.of("Content-Type", "text/xml")));
   }



   /*******************************************************************************
    ** Type, capabilities and header dialect.
    *******************************************************************************/
   @Test
   void describesItself()
   {
      CloudFrontCdnProvider cloudFront = new CloudFrontCdnProvider();
      assertThat(cloudFront.getType()).isEqualTo(CdnProviderType.CLOUDFRONT);
      assertThat(cloudFront.getCapabilities(config(null))).containsExactlyInAnyOrder(CdnCapability.PURGE_URL, CdnCapability.PURGE_WILDCARD,
         CdnCapability.PURGE_ALL, CdnCapability.STATS_IMPORT, CdnCapability.CONFIG_READ);
      assertThat(cloudFront.getHeaderDialect()).isEqualTo(CdnHeaderDialect.NO_TAGS);
   }



   /*******************************************************************************
    ** CreateInvalidation carries the paths, their count and the caller
    ** reference, signed for us-east-1; resending the batch sends the same
    ** CallerReference (CloudFront then returns the existing invalidation).
    *******************************************************************************/
   @Test
   void createInvalidationRequestShape() throws Exception
   {
      xml("POST", BASE + "/invalidation", 201, "invalidation-in-progress.xml");

      CdnPurgeBatch batch = new CdnPurgeBatch(CdnPurgeKind.PATHS, List.of("/blog/quick-bits/my-post*", "v1/blog-posts/123*"), "purge-request-42");
      CdnPurgeResult result = provider().purge(config(null), batch);
      CdnPurgeResult again = provider().purge(config(null), batch);

      assertThat(result.state()).isEqualTo(CdnPurgeResult.State.IN_PROGRESS);
      assertThat(result.providerRequestId()).isEqualTo("I2J0I21PCUYOIK");
      assertThat(result.httpStatus()).isEqualTo(201);
      assertThat(result.message()).contains("2 path(s)");
      assertThat(again.providerRequestId()).isEqualTo(result.providerRequestId());

      List<StubHttpServer.Recorded> sent = stub.requests("POST", BASE + "/invalidation");
      assertThat(sent).hasSize(2);
      for(StubHttpServer.Recorded request : sent)
      {
         assertThat(request.header("Authorization")).startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/").contains("/us-east-1/cloudfront/aws4_request");
         assertThat(request.xpath("local-name(/*)")).isEqualTo("InvalidationBatch");
         assertThat(request.xpath("//*[local-name()='CallerReference']")).isEqualTo("purge-request-42");
         assertThat(request.xpath("//*[local-name()='Paths']/*[local-name()='Quantity']")).isEqualTo("2");
         assertThat(request.xpath("(//*[local-name()='Path'])[1]")).isEqualTo("/blog/quick-bits/my-post*");
         assertThat(request.xpath("(//*[local-name()='Path'])[2]")).isEqualTo("/v1/blog-posts/123*");
      }
      assertThat(sent.get(0).body()).isEqualTo(sent.get(1).body());
   }



   /*******************************************************************************
    ** An ALL batch invalidates "/*"; an already-completed answer is SUCCEEDED.
    *******************************************************************************/
   @Test
   void purgeAll() throws Exception
   {
      xml("POST", BASE + "/invalidation", 201, "invalidation-completed.xml");

      CdnPurgeResult result = provider().purge(config(null), new CdnPurgeBatch(CdnPurgeKind.ALL, null, "purge-all-1"));

      assertThat(result.state()).isEqualTo(CdnPurgeResult.State.SUCCEEDED);
      StubHttpServer.Recorded request = stub.requests("POST", BASE + "/invalidation").get(0);
      assertThat(request.xpath("//*[local-name()='Path']")).isEqualTo("/*");
      assertThat(request.xpath("//*[local-name()='Quantity']")).isEqualTo("1");
      assertThat(request.xpath("//*[local-name()='CallerReference']")).isEqualTo("purge-all-1");
   }



   /*******************************************************************************
    ** Tag and host batches are refused (the planner converts them); empty
    ** path batches are skipped without a call; a missing distribution id is
    ** permanent.
    *******************************************************************************/
   @Test
   void refusedBatches() throws Exception
   {
      assertThatThrownBy(() -> provider().purge(config(null), new CdnPurgeBatch(CdnPurgeKind.TAG, List.of("post:1"), "r")))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse())
         .hasMessageContaining("TAG");
      assertThatThrownBy(() -> provider().purge(config(null), new CdnPurgeBatch(CdnPurgeKind.HOST, List.of("www.kof22.com"), "r")))
         .isInstanceOf(CdnException.class);
      assertThat(provider().purge(config(null), new CdnPurgeBatch(CdnPurgeKind.PATHS, List.of(), "r")).state()).isEqualTo(CdnPurgeResult.State.SKIPPED);

      CdnProviderConfig noId = new CdnProviderConfig("x", CdnProviderType.CLOUDFRONT, " ", "CDN_AWS", null);
      assertThatThrownBy(() -> provider().purge(noId, new CdnPurgeBatch(CdnPurgeKind.PATHS, List.of("/a"), "r")))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse())
         .hasMessageContaining("no CloudFront distribution id");
      assertThat(stub.requests("POST", BASE + "/invalidation")).isEmpty();
   }



   /*******************************************************************************
    ** Error codes map to retryable or permanent failures.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "TooManyInvalidationsInProgress, 400, true",
      "Throttling,                     400, true",
      "ServiceUnavailable,             503, true",
      "AccessDenied,                   403, false",
      "InvalidArgument,                400, false",
      "NoSuchDistribution,             404, false"
   })
   void errorMapping(String code, int status, boolean retryable)
   {
      xml("POST", BASE + "/invalidation", status, "error-" + code + ".xml");

      assertThatThrownBy(() -> provider().purge(config(null), new CdnPurgeBatch(CdnPurgeKind.PATHS, List.of("/a*"), "r-" + code)))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isEqualTo(retryable))
         .hasMessageContaining(code)
         .hasMessageContaining("HTTP " + status)
         .hasMessageNotContaining("test-secret");
      assertThat(stub.requests("POST", BASE + "/invalidation")).as("no SDK-level retries").hasSize(1);
   }



   /*******************************************************************************
    ** A connection failure is retryable; other SDK failures are not.
    *******************************************************************************/
   @Test
   void clientFailures() throws Exception
   {
      int closedPort;
      try(ServerSocket socket = new ServerSocket(0))
      {
         closedPort = socket.getLocalPort();
      }
      CloudFrontCdnProvider unreachable = new CloudFrontCdnProvider(new CloudFrontClientFactory(URI.create("http://127.0.0.1:" + closedPort),
         StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDTEST", "test-secret"))));

      assertThatThrownBy(() -> unreachable.checkPurge(config(null), "I1"))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isTrue())
         .hasMessageContaining("failed to reach AWS");

      CdnException generic = CloudFrontCdnProvider.toCdnException("Op", SdkException.builder().message("boom").build());
      assertThat(generic.isRetryable()).isFalse();
      assertThat(generic.getMessage()).isEqualTo("Op failed: boom");
   }



   /*******************************************************************************
    ** GetInvalidation: InProgress then Completed.
    *******************************************************************************/
   @Test
   void checkPurge() throws Exception
   {
      String path = BASE + "/invalidation/I2J0I21PCUYOIK";
      xml("GET", path, 200, "invalidation-in-progress.xml");
      CdnPurgeResult inProgress = provider().checkPurge(config(null), "I2J0I21PCUYOIK");
      assertThat(inProgress.state()).isEqualTo(CdnPurgeResult.State.IN_PROGRESS);
      assertThat(inProgress.providerRequestId()).isEqualTo("I2J0I21PCUYOIK");

      xml("GET", path, 200, "invalidation-completed.xml");
      CdnPurgeResult completed = provider().checkPurge(config(null), "I2J0I21PCUYOIK");
      assertThat(completed.state()).isEqualTo(CdnPurgeResult.State.SUCCEEDED);
      assertThat(completed.httpStatus()).isEqualTo(200);
      assertThat(completed.message()).isEqualTo("Invalidation Completed");
   }



   /*******************************************************************************
    ** Test connection reads the distribution, proves invalidation access and
    ** reports the origin-verify header without its value.
    *******************************************************************************/
   @Test
   void testConnection()
   {
      xml("GET", BASE + "/config", 200, "distribution-config.xml");
      xml("GET", BASE, 200, "distribution.xml");
      xml("GET", BASE + "/invalidation", 200, "invalidation-list.xml");

      CloudFrontConnectionResult result = provider().testConnection(config(null));

      assertThat(result.ok()).isTrue();
      assertThat(result.distributionDomainName()).isEqualTo("d111111abcdef8.cloudfront.net");
      assertThat(result.status()).isEqualTo("Deployed");
      assertThat(result.aliases()).containsExactly("api.kof22.com", "kof22.com", "s3.kof22.com", "www.kof22.com");
      assertThat(result.originDomain()).isEqualTo("origin.kof22.com");
      assertThat(result.originPort()).isEqualTo(8443);
      assertThat(result.originVerifyHeaderConfigured()).isTrue();
      assertThat(result.message()).contains("X-Origin-Verify configured").contains("(Deployed)");
      assertThat(result.toString()).doesNotContain(SECRET);
      assertThat(stub.requests("GET", BASE + "/invalidation")).singleElement()
         .extracting(StubHttpServer.Recorded::uri).asString().contains("MaxItems=1");
   }



   /*******************************************************************************
    ** Without GetDistribution permission the test still passes, minus the
    ** domain name and status; a different header name is reported missing.
    *******************************************************************************/
   @Test
   void testConnectionWithoutGetDistribution()
   {
      xml("GET", BASE + "/config", 200, "distribution-config.xml");
      xml("GET", BASE, 403, "error-AccessDenied.xml");
      xml("GET", BASE + "/invalidation", 200, "invalidation-list.xml");

      CloudFrontConnectionResult result = provider().testConnection(config(Map.of(CloudFrontCdnProvider.SETTING_ORIGIN_VERIFY_HEADER, "X-Other-Verify")));

      assertThat(result.ok()).isTrue();
      assertThat(result.distributionDomainName()).isNull();
      assertThat(result.status()).isNull();
      assertThat(result.originVerifyHeaderConfigured()).isFalse();
      assertThat(result.message()).contains("X-Other-Verify NOT configured").contains("GetDistribution not permitted");
   }



   /*******************************************************************************
    ** Missing IAM scope, credentials or distribution id fail the test.
    *******************************************************************************/
   @Test
   void testConnectionFailures()
   {
      xml("GET", BASE + "/config", 200, "distribution-config.xml");
      xml("GET", BASE + "/invalidation", 403, "error-AccessDenied.xml");

      CloudFrontConnectionResult denied = provider().testConnection(config(null));
      assertThat(denied.ok()).isFalse();
      assertThat(denied.message()).contains("AccessDenied");
      assertThat(denied.aliases()).isEmpty();

      CloudFrontCdnProvider noCredentials = new CloudFrontCdnProvider(new CloudFrontClientFactory(name -> null, URI.create(stub.baseUrl()), null));
      assertThat(noCredentials.testConnection(config(null)).message()).contains("CDN_AWS_ACCESS_KEY_ID");

      CdnProviderConfig noId = new CdnProviderConfig("x", CdnProviderType.CLOUDFRONT, null, "CDN_AWS", null);
      assertThat(provider().testConnection(noId).ok()).isFalse();
   }



   /*******************************************************************************
    ** readConfig returns the drift facts; header values never leave the SDK.
    *******************************************************************************/
   @Test
   void readConfig() throws Exception
   {
      xml("GET", BASE + "/config", 200, "distribution-config.xml");

      CloudFrontConfigSnapshot snapshot = provider().readConfig(config(null));

      assertThat(snapshot.enabled()).isTrue();
      assertThat(snapshot.priceClass()).isEqualTo("PriceClass_All");
      assertThat(snapshot.httpVersion()).isEqualTo("http2and3");
      assertThat(snapshot.originProtocolPolicy()).isEqualTo("https-only");
      assertThat(snapshot.originHeaderNames()).containsExactly("X-Origin-Verify");
      assertThat(snapshot.behaviors()).extracting(CloudFrontConfigSnapshot.Behavior::pathPattern)
         .containsExactly("/_next/static/*", "/api/*", "/v1/me*", "*");
      assertThat(snapshot.behavior("/api/*")).get().extracting(CloudFrontConfigSnapshot.Behavior::cachePolicyId)
         .isEqualTo(CloudFrontDriftCheck.MANAGED_CACHING_DISABLED_POLICY_ID);
      assertThat(snapshot.toString()).doesNotContain(SECRET);
      assertThat(CloudFrontDriftCheck.compare(snapshot, Fixtures.load("cloudfront/expected-config.json"))).isEmpty();

      xml("GET", BASE + "/config", 404, "error-NoSuchDistribution.xml");
      assertThatThrownBy(() -> provider().readConfig(config(null)))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse())
         .hasMessageContaining("NoSuchDistribution");
   }
}
