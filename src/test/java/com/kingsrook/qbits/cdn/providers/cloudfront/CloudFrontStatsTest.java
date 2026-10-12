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


import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.spi.CdnEdgeStat;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/*******************************************************************************
 ** Edge stats import. CloudWatch in SDK 2.41 speaks smithy RPC v2 CBOR, so
 ** CloudWatchClient is mocked rather than stubbed over HTTP.
 *******************************************************************************/
class CloudFrontStatsTest
{
   private static final CdnProviderConfig CONFIG = new CdnProviderConfig("kof22-cloudfront", CdnProviderType.CLOUDFRONT, "E2EXAMPLE123", "CDN_AWS", null);

   private static final Instant DAY1 = Instant.parse("2026-10-10T00:00:00Z");
   private static final Instant DAY2 = Instant.parse("2026-10-11T00:00:00Z");



   /*******************************************************************************
    ** An adapter whose CloudWatch client is the given mock.
    *******************************************************************************/
   private static CloudFrontCdnProvider provider(CloudWatchClient cloudWatch)
   {
      return new CloudFrontCdnProvider(new CloudFrontClientFactory(name -> null, null, null)
      {
         /***************************************************************************
          ** The mock.
          ***************************************************************************/
         @Override
         public CloudWatchClient cloudWatch(CdnProviderConfig config)
         {
            return cloudWatch;
         }
      });
   }



   /*******************************************************************************
    ** One metric's datapoints.
    *******************************************************************************/
   private static MetricDataResult result(String id, List<Instant> timestamps, List<Double> values)
   {
      return MetricDataResult.builder().id(id).timestamps(timestamps).values(values).build();
   }



   /*******************************************************************************
    ** Two pages are merged into one row per UTC day; a missing CacheHitRate
    ** datapoint is null; the queries ask for daily sums and averages on the
    ** distribution's global metrics.
    *******************************************************************************/
   @Test
   void importsDailyStats() throws Exception
   {
      CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
      GetMetricDataResponse page1 = GetMetricDataResponse.builder()
         .metricDataResults(
            result("requests", List.of(DAY1, DAY2), List.of(1200.0, 3400.0)),
            result("bytes", List.of(DAY1, DAY2), List.of(5.5e8, 9.0e8)),
            result("hit", List.of(DAY2), List.of(87.5)))
         .nextToken("page-2")
         .build();
      GetMetricDataResponse page2 = GetMetricDataResponse.builder()
         .metricDataResults(
            result("err4xx", List.of(DAY1, DAY2), List.of(1.25, 0.5)),
            result("err5xx", List.of(DAY1, DAY2), List.of(0.0, 0.1)))
         .build();
      when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenReturn(page1, page2);

      List<CdnEdgeStat> stats = provider(cloudWatch).importStats(CONFIG, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-11"));

      assertThat(stats).containsExactly(
         new CdnEdgeStat(LocalDate.parse("2026-10-10"), 1200, 550_000_000L, 1.25, 0.0, null),
         new CdnEdgeStat(LocalDate.parse("2026-10-11"), 3400, 900_000_000L, 0.5, 0.1, 87.5));

      ArgumentCaptor<GetMetricDataRequest> captor = ArgumentCaptor.forClass(GetMetricDataRequest.class);
      verify(cloudWatch, times(2)).getMetricData(captor.capture());
      GetMetricDataRequest first = captor.getAllValues().get(0);
      assertThat(first.startTime()).isEqualTo(DAY1);
      assertThat(first.endTime()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
      assertThat(first.nextToken()).isNull();
      assertThat(captor.getAllValues().get(1).nextToken()).isEqualTo("page-2");

      Map<String, String> statById = new HashMap<>();
      for(MetricDataQuery query : first.metricDataQueries())
      {
         assertThat(query.metricStat().period()).isEqualTo(86_400);
         assertThat(query.metricStat().metric().namespace()).isEqualTo("AWS/CloudFront");
         assertThat(query.metricStat().metric().dimensions()).containsExactly(
            Dimension.builder().name("DistributionId").value("E2EXAMPLE123").build(),
            Dimension.builder().name("Region").value("Global").build());
         statById.put(query.metricStat().metric().metricName(), query.metricStat().stat());
      }
      assertThat(statById).containsExactlyInAnyOrderEntriesOf(Map.of(
         "Requests", "Sum", "BytesDownloaded", "Sum", "4xxErrorRate", "Average", "5xxErrorRate", "Average", "CacheHitRate", "Average"));
   }



   /*******************************************************************************
    ** No datapoints at all: no rows.
    *******************************************************************************/
   @Test
   void noData() throws Exception
   {
      CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
      when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenReturn(GetMetricDataResponse.builder().nextToken("").build());
      assertThat(provider(cloudWatch).importStats(CONFIG, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-10"))).isEmpty();
   }



   /*******************************************************************************
    ** CloudWatch failures map like CloudFront's; a reversed range is refused.
    *******************************************************************************/
   @Test
   void failures()
   {
      CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
      when(cloudWatch.getMetricData(any(GetMetricDataRequest.class))).thenThrow(CloudWatchException.builder()
         .statusCode(500)
         .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalServiceError").errorMessage("try later").build())
         .build());

      assertThatThrownBy(() -> provider(cloudWatch).importStats(CONFIG, LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-11")))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isTrue())
         .hasMessageContaining("GetMetricData failed: InternalServiceError (HTTP 500): try later");

      assertThatThrownBy(() -> provider(cloudWatch).importStats(CONFIG, LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-10")))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse());
   }
}
