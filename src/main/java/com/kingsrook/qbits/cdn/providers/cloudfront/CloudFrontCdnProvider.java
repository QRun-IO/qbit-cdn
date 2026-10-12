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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnEdgeStat;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationResponse;
import software.amazon.awssdk.services.cloudfront.model.Distribution;
import software.amazon.awssdk.services.cloudfront.model.GetInvalidationResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;


/*******************************************************************************
 ** Amazon CloudFront adapter. CloudFront has no tag purge, so the planner
 ** turns tags into paths before a batch reaches {@link #purge}. Invalidations
 ** are asynchronous: purge returns IN_PROGRESS with the invalidation id, and
 ** {@link #checkPurge} polls GetInvalidation.
 **
 ** <p>Configuration: {@code zoneOrDistributionId} is the distribution id;
 ** {@code secretRef} is the env var prefix for the IAM key (see
 ** {@link CloudFrontClientFactory}); the optional setting
 ** {@code originVerifyHeader} names the origin custom header that "Test
 ** connection" looks for (default X-Origin-Verify).</p>
 **
 ** <p>Besides the SPI it offers the CloudFront-only operations behind the
 ** "Test connection", "Import edge stats" and "Check config drift" actions:
 ** {@link #testConnection}, {@link #importStats} and {@link #readConfig}
 ** (compare the result with {@link CloudFrontDriftCheck#compare}).</p>
 *******************************************************************************/
public class CloudFrontCdnProvider implements CdnProviderInterface
{
   /** Default origin custom header that proves a request came through CloudFront. */
   public static final String DEFAULT_ORIGIN_VERIFY_HEADER = "X-Origin-Verify";

   /** Setting naming the origin verify header. */
   public static final String SETTING_ORIGIN_VERIFY_HEADER = "originVerifyHeader";

   static final String STATUS_COMPLETED = "Completed";
   static final int    SECONDS_PER_DAY  = 86_400;

   private static final Set<String> RETRYABLE_CODES = Set.of(
      "TooManyInvalidationsInProgress", "Throttling", "ThrottlingException", "RequestLimitExceeded", "ServiceUnavailable", "InternalError");

   private final CloudFrontClientFactory clientFactory;



   /*******************************************************************************
    ** Production constructor (credentials from environment variables).
    *******************************************************************************/
   public CloudFrontCdnProvider()
   {
      this(new CloudFrontClientFactory());
   }



   /*******************************************************************************
    ** Constructor with a client factory (tests: endpoint override, static
    ** credentials, mocked CloudWatch).
    *******************************************************************************/
   public CloudFrontCdnProvider(CloudFrontClientFactory clientFactory)
   {
      this.clientFactory = clientFactory;
   }



   /*******************************************************************************
    ** Getter for type.
    *******************************************************************************/
   @Override
   public CdnProviderType getType()
   {
      return CdnProviderType.CLOUDFRONT;
   }



   /*******************************************************************************
    ** Paths, wildcards and the whole distribution; stats and config reads.
    ** No tag purge and no CDN-Cache-Control.
    *******************************************************************************/
   @Override
   public Set<CdnCapability> getCapabilities(CdnProviderConfig config)
   {
      return Set.of(CdnCapability.PURGE_URL, CdnCapability.PURGE_WILDCARD, CdnCapability.PURGE_ALL, CdnCapability.STATS_IMPORT, CdnCapability.CONFIG_READ);
   }



   /*******************************************************************************
    ** No tag header: tags never leave the qbit on CloudFront.
    *******************************************************************************/
   @Override
   public CdnHeaderDialect getHeaderDialect()
   {
      return CdnHeaderDialect.NO_TAGS;
   }



   /*******************************************************************************
    ** CreateInvalidation for a PATHS or ALL batch. The batch's caller reference
    ** is the invalidation's CallerReference, so resending the same batch
    ** returns the existing invalidation instead of creating a second one.
    *******************************************************************************/
   @Override
   public CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch) throws CdnException
   {
      List<String> paths = invalidationPaths(batch);
      if(paths.isEmpty())
      {
         return CdnPurgeResult.skipped("No paths to invalidate");
      }

      String distributionId = distributionId(config);
      try(CloudFrontClient client = clientFactory.cloudFront(config))
      {
         CreateInvalidationResponse response = client.createInvalidation(r -> r
            .distributionId(distributionId)
            .invalidationBatch(b -> b
               .callerReference(batch.callerReference())
               .paths(p -> p.quantity(paths.size()).items(paths))));

         String id = response.invalidation().id();
         int httpStatus = response.sdkHttpResponse().statusCode();
         CdnPurgeResult.State state = STATUS_COMPLETED.equals(response.invalidation().status()) ? CdnPurgeResult.State.SUCCEEDED : CdnPurgeResult.State.IN_PROGRESS;
         return new CdnPurgeResult(state, id, httpStatus, "Invalidation " + id + " for " + paths.size() + " path(s)");
      }
      catch(SdkException e)
      {
         throw toCdnException("CreateInvalidation", e);
      }
   }



   /*******************************************************************************
    ** The invalidation paths for a batch: PATHS with a leading "/" ensured,
    ** ALL as "/*". Other kinds are refused.
    *******************************************************************************/
   private static List<String> invalidationPaths(CdnPurgeBatch batch) throws CdnException
   {
      switch(batch.kind())
      {
         case PATHS:
            return batch.items().stream().map(p -> p.startsWith("/") ? p : "/" + p).toList();
         case ALL:
            return List.of("/*");
         default:
            throw new CdnException("CloudFront cannot purge " + batch.kind() + " batches; the path planner must turn them into paths", false, null);
      }
   }



   /*******************************************************************************
    ** GetInvalidation: Completed is SUCCEEDED, anything else IN_PROGRESS.
    *******************************************************************************/
   @Override
   public CdnPurgeResult checkPurge(CdnProviderConfig config, String providerRequestId) throws CdnException
   {
      String distributionId = distributionId(config);
      try(CloudFrontClient client = clientFactory.cloudFront(config))
      {
         GetInvalidationResponse response = client.getInvalidation(r -> r.distributionId(distributionId).id(providerRequestId));
         String status = response.invalidation().status();
         CdnPurgeResult.State state = STATUS_COMPLETED.equals(status) ? CdnPurgeResult.State.SUCCEEDED : CdnPurgeResult.State.IN_PROGRESS;
         return new CdnPurgeResult(state, providerRequestId, response.sdkHttpResponse().statusCode(), "Invalidation " + status);
      }
      catch(SdkException e)
      {
         throw toCdnException("GetInvalidation", e);
      }
   }



   /*******************************************************************************
    ** "Test connection": credentials, the distribution, the IAM scope
    ** (GetDistributionConfig and ListInvalidations must both be allowed) and
    ** whether the origin-verify header is configured (its value is never
    ** read out). GetDistribution adds the domain name and status when the
    ** key is allowed to call it; it is optional because the section 7.5 IAM
    ** policy does not grant it. Never throws: failures come back as ok=false.
    *******************************************************************************/
   public CloudFrontConnectionResult testConnection(CdnProviderConfig config)
   {
      String headerName = config.settings().getOrDefault(SETTING_ORIGIN_VERIFY_HEADER, DEFAULT_ORIGIN_VERIFY_HEADER);
      try
      {
         String distributionId = distributionId(config);
         try(CloudFrontClient client = clientFactory.cloudFront(config))
         {
            CloudFrontConfigSnapshot snapshot;
            try
            {
               snapshot = CloudFrontConfigSnapshot.from(client.getDistributionConfig(r -> r.id(distributionId)).distributionConfig());
               client.listInvalidations(r -> r.distributionId(distributionId).maxItems("1"));
            }
            catch(SdkException e)
            {
               return CloudFrontConnectionResult.failed(toCdnException("Test connection", e).getMessage());
            }

            String domainName = null;
            String status = null;
            String note = "";
            try
            {
               Distribution distribution = client.getDistribution(r -> r.id(distributionId)).distribution();
               domainName = distribution.domainName();
               status = distribution.status();
            }
            catch(AwsServiceException e)
            {
               note = "; GetDistribution not permitted, so domain name and status are unknown";
            }

            boolean headerConfigured = snapshot.hasOriginHeader(headerName);
            String message = "Distribution " + distributionId + " reachable with invalidation access"
               + (status == null ? "" : " (" + status + ")")
               + "; origin " + snapshot.originDomain() + ":" + snapshot.originPort()
               + "; " + headerName + (headerConfigured ? " configured" : " NOT configured")
               + note;
            return new CloudFrontConnectionResult(true, domainName, snapshot.aliases(), snapshot.originDomain(), snapshot.originPort(), headerConfigured, status, message);
         }
      }
      catch(CdnException e)
      {
         return CloudFrontConnectionResult.failed(e.getMessage());
      }
   }



   /*******************************************************************************
    ** The distribution config facts used for drift checks (GetDistributionConfig).
    *******************************************************************************/
   public CloudFrontConfigSnapshot readConfig(CdnProviderConfig config) throws CdnException
   {
      String distributionId = distributionId(config);
      try(CloudFrontClient client = clientFactory.cloudFront(config))
      {
         return CloudFrontConfigSnapshot.from(client.getDistributionConfig(r -> r.id(distributionId)).distributionConfig());
      }
      catch(SdkException e)
      {
         throw toCdnException("GetDistributionConfig", e);
      }
   }



   /*******************************************************************************
    ** Daily edge stats from CloudWatch (namespace AWS/CloudFront, dimensions
    ** DistributionId and Region=Global, period one day) for the UTC days
    ** from..to inclusive. Days without any datapoint are left out; a metric
    ** without a datapoint on a day is 0 for counts and null for rates
    ** (CacheHitRate needs CloudFront's additional metrics to be enabled).
    *******************************************************************************/
   public List<CdnEdgeStat> importStats(CdnProviderConfig config, LocalDate from, LocalDate to) throws CdnException
   {
      if(to.isBefore(from))
      {
         throw new CdnException("Stats range ends (" + to + ") before it starts (" + from + ")", false, null);
      }

      String distributionId = distributionId(config);
      List<MetricDataQuery> queries = List.of(
         query("requests", "Requests", "Sum", distributionId),
         query("bytes", "BytesDownloaded", "Sum", distributionId),
         query("err4xx", "4xxErrorRate", "Average", distributionId),
         query("err5xx", "5xxErrorRate", "Average", distributionId),
         query("hit", "CacheHitRate", "Average", distributionId));

      Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
      Instant end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
      Map<LocalDate, Map<String, Double>> byDay = new TreeMap<>();

      try(CloudWatchClient client = clientFactory.cloudWatch(config))
      {
         String nextToken = null;
         do
         {
            GetMetricDataRequest request = GetMetricDataRequest.builder()
               .metricDataQueries(queries)
               .startTime(start)
               .endTime(end)
               .scanBy(ScanBy.TIMESTAMP_ASCENDING)
               .nextToken(nextToken)
               .build();
            GetMetricDataResponse response = client.getMetricData(request);
            for(MetricDataResult result : response.metricDataResults())
            {
               for(int i = 0; i < result.timestamps().size() && i < result.values().size(); i++)
               {
                  LocalDate day = result.timestamps().get(i).atZone(ZoneOffset.UTC).toLocalDate();
                  byDay.computeIfAbsent(day, d -> new HashMap<>()).put(result.id(), result.values().get(i));
               }
            }
            nextToken = response.nextToken();
         }
         while(nextToken != null && !nextToken.isEmpty());
      }
      catch(SdkException e)
      {
         throw toCdnException("GetMetricData", e);
      }

      List<CdnEdgeStat> stats = new ArrayList<>();
      byDay.forEach((day, values) -> stats.add(new CdnEdgeStat(day,
         Math.round(values.getOrDefault("requests", 0.0)),
         Math.round(values.getOrDefault("bytes", 0.0)),
         values.get("err4xx"),
         values.get("err5xx"),
         values.get("hit"))));
      return stats;
   }



   /*******************************************************************************
    ** One daily CloudFront metric query.
    *******************************************************************************/
   private static MetricDataQuery query(String id, String metricName, String stat, String distributionId)
   {
      Metric metric = Metric.builder()
         .namespace("AWS/CloudFront")
         .metricName(metricName)
         .dimensions(Dimension.builder().name("DistributionId").value(distributionId).build(),
            Dimension.builder().name("Region").value("Global").build())
         .build();
      return MetricDataQuery.builder()
         .id(id)
         .metricStat(MetricStat.builder().metric(metric).period(SECONDS_PER_DAY).stat(stat).build())
         .returnData(true)
         .build();
   }



   /*******************************************************************************
    ** The configured distribution id.
    *******************************************************************************/
   private static String distributionId(CdnProviderConfig config) throws CdnException
   {
      String id = config.zoneOrDistributionId();
      if(id == null || id.isBlank())
      {
         throw new CdnException("CDN provider " + config.name() + " has no CloudFront distribution id", false, null);
      }
      return id.strip();
   }



   /*******************************************************************************
    ** Map an SDK failure. Retryable: TooManyInvalidationsInProgress, throttling,
    ** HTTP 429 and 5xx, and client-side (network) failures. Everything else,
    ** including AccessDenied, InvalidArgument and NoSuchDistribution, is
    ** permanent. Messages carry the AWS error code and message, never
    ** credentials.
    *******************************************************************************/
   static CdnException toCdnException(String operation, SdkException e)
   {
      if(e instanceof AwsServiceException service)
      {
         String code = service.awsErrorDetails() == null ? null : service.awsErrorDetails().errorCode();
         String detail = service.awsErrorDetails() == null ? null : service.awsErrorDetails().errorMessage();
         int status = service.statusCode();
         boolean retryable = (code != null && RETRYABLE_CODES.contains(code)) || service.isThrottlingException() || status == 429 || status >= 500;
         String message = operation + " failed: " + code + " (HTTP " + status + ")" + (detail == null ? "" : ": " + detail);
         return new CdnException(message, retryable, e);
      }
      if(e instanceof SdkClientException)
      {
         return new CdnException(operation + " failed to reach AWS: " + e.getMessage(), true, e);
      }
      return new CdnException(operation + " failed: " + e.getMessage(), false, e);
   }
}
