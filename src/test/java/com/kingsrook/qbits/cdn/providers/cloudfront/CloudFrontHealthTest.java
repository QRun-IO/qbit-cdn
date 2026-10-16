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
import java.util.Map;
import com.kingsrook.qbits.cdn.spi.CdnHealth;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CustomHeaders;
import software.amazon.awssdk.services.cloudfront.model.DefaultCacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.Distribution;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionConfigRequest;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionRequest;
import software.amazon.awssdk.services.cloudfront.model.GetDistributionResponse;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginCustomHeader;
import software.amazon.awssdk.services.cloudfront.model.Origins;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/*******************************************************************************
 ** The CloudFront health probe against mocked CloudFront and CloudWatch
 ** clients (no network).
 *******************************************************************************/
class CloudFrontHealthTest
{
   private static final String            DISTRIBUTION = "E2EXAMPLE123";
   private static final String            SECRET       = "FIXTURE-SECRET-NEVER-LEAK";
   private static final CdnProviderConfig CONFIG       = new CdnProviderConfig("kof22-cloudfront", CdnProviderType.CLOUDFRONT, DISTRIBUTION, "CDN_AWS", null);

   private CloudFrontClient cloudFront;
   private CloudWatchClient cloudWatch;



   /*******************************************************************************
    ** Fresh mocks; by default CloudWatch has no datapoints.
    *******************************************************************************/
   @BeforeEach
   void mocks()
   {
      cloudFront = mock(CloudFrontClient.class);
      cloudWatch = mock(CloudWatchClient.class);
      errorRate();
   }



   /*******************************************************************************
    ** An adapter whose clients are the mocks.
    *******************************************************************************/
   private CloudFrontCdnProvider provider()
   {
      return new CloudFrontCdnProvider(new CloudFrontClientFactory(name -> null, null, null)
      {
         /***************************************************************************
          ** The CloudFront mock.
          ***************************************************************************/
         @Override
         public CloudFrontClient cloudFront(CdnProviderConfig config)
         {
            return cloudFront;
         }



         /***************************************************************************
          ** The CloudWatch mock.
          ***************************************************************************/
         @Override
         public CloudWatchClient cloudWatch(CdnProviderConfig config)
         {
            return cloudWatch;
         }
      });
   }



   /*******************************************************************************
    ** A distribution config, enabled or not, whose origin sends the verify header.
    *******************************************************************************/
   private static DistributionConfig distributionConfig(boolean enabled)
   {
      Origin origin = Origin.builder().id("origin").domainName("origin.kof22.com")
         .customHeaders(CustomHeaders.builder().quantity(1).items(OriginCustomHeader.builder().headerName("X-Origin-Verify").headerValue(SECRET).build()).build())
         .build();
      return DistributionConfig.builder().enabled(enabled)
         .origins(Origins.builder().quantity(1).items(origin).build())
         .defaultCacheBehavior(DefaultCacheBehavior.builder().targetOriginId("origin").build())
         .build();
   }



   /*******************************************************************************
    ** GetDistribution answers with this status and enabled flag.
    *******************************************************************************/
   private void distribution(String status, boolean enabled)
   {
      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenReturn(GetDistributionResponse.builder()
         .distribution(Distribution.builder().id(DISTRIBUTION).status(status).domainName("d1.cloudfront.net").distributionConfig(distributionConfig(enabled)).build())
         .build());
   }



   /*******************************************************************************
    ** CloudWatch answers with these 5xxErrorRate datapoints.
    *******************************************************************************/
   private void errorRate(Double... values)
   {
      when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenReturn(GetMetricDataResponse.builder()
         .metricDataResults(MetricDataResult.builder().id("err5xx").values(values).build()).build());
   }



   /*******************************************************************************
    ** A CloudFront service error.
    *******************************************************************************/
   private static AwsServiceException error(String code, int status)
   {
      return CloudFrontException.builder().statusCode(status)
         .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(SECRET).build()).build();
   }



   /*******************************************************************************
    ** Enabled and Deployed is UP; the 5xx rate is averaged into the details from
    ** a five-minute Average query on the distribution's global metric.
    *******************************************************************************/
   @Test
   void deployedIsUp()
   {
      distribution("Deployed", true);
      errorRate(1.0, 2.0);

      CdnHealth health = provider().checkHealth(CONFIG);

      assertThat(health.state()).isEqualTo(CdnHealth.State.UP);
      assertThat(health.message()).isEqualTo("CloudFront distribution is enabled and deployed");
      assertThat(health.details()).containsExactlyInAnyOrderEntriesOf(Map.of("enabled", true, "status", "Deployed", "originHeaderConfigured", true,
         "errorRateWindowMinutes", 30, "errorRate5xxPercent", 1.5));
      ArgumentCaptor<GetMetricDataRequest> request = ArgumentCaptor.forClass(GetMetricDataRequest.class);
      verify(cloudWatch).getMetricData(request.capture());
      var stat = request.getValue().metricDataQueries().getFirst().metricStat();
      assertThat(stat.metric().metricName()).isEqualTo("5xxErrorRate");
      assertThat(stat.metric().namespace()).isEqualTo("AWS/CloudFront");
      assertThat(stat.metric().dimensions()).containsExactly(Dimension.builder().name("DistributionId").value(DISTRIBUTION).build(),
         Dimension.builder().name("Region").value("Global").build());
      assertThat(stat.stat()).isEqualTo("Average");
      assertThat(stat.period()).isEqualTo(300);
      assertThat(java.time.Duration.between(request.getValue().startTime(), request.getValue().endTime()).toMinutes()).isEqualTo(30);
      verify(cloudFront, never()).getDistributionConfig(any(GetDistributionConfigRequest.class));
      assertClean(health);
   }



   /*******************************************************************************
    ** No traffic (no datapoints) or an unreadable metric leaves the rate out.
    *******************************************************************************/
   @Test
   void errorRateIsBestEffort()
   {
      distribution("Deployed", true);
      assertThat(provider().checkHealth(CONFIG).details()).doesNotContainKey("errorRate5xxPercent").containsEntry("errorRateWindowMinutes", 30);

      when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenThrow(CloudWatchException.builder().statusCode(403)
         .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build());
      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.UP);
      assertThat(health.details()).doesNotContainKey("errorRate5xxPercent");
   }



   /*******************************************************************************
    ** A 5xx rate above 5% is DEGRADED; at 5% it is still UP.
    *******************************************************************************/
   @Test
   void highErrorRateIsDegraded()
   {
      distribution("Deployed", true);
      errorRate(5.0);
      assertThat(provider().checkHealth(CONFIG).state()).isEqualTo(CdnHealth.State.UP);

      errorRate(4.0, 9.0);
      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.DEGRADED);
      assertThat(health.message()).contains("5xx error rate");
      assertThat(health.details()).containsEntry("errorRate5xxPercent", 6.5);
   }



   /*******************************************************************************
    ** A deploying distribution is DEGRADED and says so.
    *******************************************************************************/
   @Test
   void inProgressIsDegraded()
   {
      distribution("InProgress", true);
      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.DEGRADED);
      assertThat(health.message()).contains("deploying").contains("InProgress");
      assertThat(health.details()).containsEntry("status", "InProgress");
   }



   /*******************************************************************************
    ** A disabled distribution is DOWN (and CloudWatch is not consulted); an
    ** unexpected status value is reported as UNKNOWN, never echoed.
    *******************************************************************************/
   @Test
   void disabledIsDown()
   {
      distribution(SECRET, false);
      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.DOWN);
      assertThat(health.message()).isEqualTo("CloudFront distribution is disabled");
      assertThat(health.details()).containsEntry("enabled", false).containsEntry("status", "UNKNOWN");
      verify(cloudWatch, never()).getMetricData(any(GetMetricDataRequest.class));
      assertClean(health);
   }



   /*******************************************************************************
    ** A different configured header name is reported missing: DEGRADED.
    *******************************************************************************/
   @Test
   void missingOriginHeaderIsDegraded()
   {
      distribution("Deployed", true);
      CdnProviderConfig other = new CdnProviderConfig("x", CdnProviderType.CLOUDFRONT, DISTRIBUTION, "CDN_AWS",
         Map.of(CloudFrontCdnProvider.SETTING_ORIGIN_VERIFY_HEADER, "X-Other-Verify"));
      CdnHealth health = provider().checkHealth(other);
      assertThat(health.state()).isEqualTo(CdnHealth.State.DEGRADED);
      assertThat(health.details()).containsEntry("originHeaderConfigured", false);
   }



   /*******************************************************************************
    ** Without GetDistribution (the section 7.5 policy) the probe falls back to
    ** GetDistributionConfig: UP, not testable, Enabled still checked.
    *******************************************************************************/
   @Test
   void accessDeniedFallsBackToConfig()
   {
      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(error("AccessDenied", 403));
      when(cloudFront.getDistributionConfig(any(GetDistributionConfigRequest.class)))
         .thenReturn(GetDistributionConfigResponse.builder().distributionConfig(distributionConfig(true)).build());

      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.UP);
      assertThat(health.message()).isEqualTo("CloudFront configured; distribution status not testable with current permissions");
      assertThat(health.details()).containsEntry("testable", false).containsEntry("reason", "credentials lack cloudfront:GetDistribution")
         .containsEntry("enabled", true).containsEntry("status", "UNKNOWN");
      assertClean(health);

      when(cloudFront.getDistributionConfig(any(GetDistributionConfigRequest.class)))
         .thenReturn(GetDistributionConfigResponse.builder().distributionConfig(distributionConfig(false)).build());
      assertThat(provider().checkHealth(CONFIG).state()).isEqualTo(CdnHealth.State.DOWN);
   }



   /*******************************************************************************
    ** When neither read is permitted the check is UP and not testable.
    *******************************************************************************/
   @Test
   void noReadPermissionIsUpNotTestable()
   {
      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(error("AccessDenied", 403));
      when(cloudFront.getDistributionConfig(any(GetDistributionConfigRequest.class))).thenThrow(error(null, 403));

      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(CdnHealth.State.UP);
      assertThat(health.details()).containsExactlyInAnyOrderEntriesOf(Map.of("testable", false,
         "reason", "credentials lack cloudfront:GetDistribution and cloudfront:GetDistributionConfig"));
      verify(cloudWatch, never()).getMetricData(any(GetMetricDataRequest.class));
   }



   /*******************************************************************************
    ** AWS errors: missing distribution and rejected credentials are DOWN,
    ** throttling and AWS-side errors DEGRADED; also after the config fallback.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "NoSuchDistribution, 404, DOWN, CloudFront distribution not found",
      "InvalidClientTokenId, 403, DOWN, CloudFront rejected the status check",
      "Throttling, 400, DEGRADED, CloudFront API throttled or unavailable; distribution status not checked",
      "ServiceUnavailable, 503, DEGRADED, CloudFront API throttled or unavailable; distribution status not checked",
   })
   void apiErrors(String code, int status, CdnHealth.State expected, String message)
   {
      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(error(code, status));
      CdnHealth health = provider().checkHealth(CONFIG);
      assertThat(health.state()).isEqualTo(expected);
      assertThat(health.message()).isEqualTo(message);
      assertClean(health);

      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(error("AccessDenied", 403));
      when(cloudFront.getDistributionConfig(any(GetDistributionConfigRequest.class))).thenThrow(error(code, status));
      assertThat(provider().checkHealth(CONFIG).state()).isEqualTo(expected);
   }



   /*******************************************************************************
    ** Network failures are DEGRADED (transient); missing credentials or
    ** distribution id are DOWN; other SDK failures are DOWN.
    *******************************************************************************/
   @Test
   void clientAndConfigFailures()
   {
      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(SdkClientException.create(SECRET));
      CdnHealth unreachable = provider().checkHealth(CONFIG);
      assertThat(unreachable.state()).isEqualTo(CdnHealth.State.DEGRADED);
      assertThat(unreachable.details()).containsEntry("transient", true);
      assertClean(unreachable);

      CdnHealth noCredentials = new CloudFrontCdnProvider(new CloudFrontClientFactory(name -> null, null, null)).checkHealth(CONFIG);
      assertThat(noCredentials.state()).isEqualTo(CdnHealth.State.DOWN);
      assertThat(noCredentials.message()).isEqualTo("CloudFront credentials are not configured");

      CdnHealth noId = provider().checkHealth(new CdnProviderConfig("x", CdnProviderType.CLOUDFRONT, " ", "CDN_AWS", null));
      assertThat(noId.state()).isEqualTo(CdnHealth.State.DOWN);
      assertThat(noId.message()).isEqualTo("CloudFront distribution id is not configured");

      when(cloudFront.getDistribution(any(GetDistributionRequest.class))).thenThrow(SdkException.builder().message(SECRET).build());
      CdnHealth other = provider().checkHealth(CONFIG);
      assertThat(other.state()).isEqualTo(CdnHealth.State.DOWN);
      assertClean(other);
   }



   /*******************************************************************************
    ** Nothing the AWS side controls as free text reaches the outcome.
    *******************************************************************************/
   private static void assertClean(CdnHealth health)
   {
      assertThat(health.message()).doesNotContain(SECRET, DISTRIBUTION, "cloudfront.net", "origin.kof22.com");
      assertThat(List.copyOf(health.details().values()).toString()).doesNotContain(SECRET, DISTRIBUTION, "cloudfront.net", "origin.kof22.com");
   }
}
