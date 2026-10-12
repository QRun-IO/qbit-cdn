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


import java.net.URI;
import java.util.function.Function;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClientBuilder;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClientBuilder;


/*******************************************************************************
 ** Builds the AWS SDK clients the CloudFront adapter uses, one per call (the
 ** adapter is stateless, so callers close what they get).
 **
 ** <p>Credentials convention: {@code CdnProviderConfig.secretRef()} is an
 ** environment variable <b>prefix</b>. The access key id is read from
 ** {@code <secretRef>_ACCESS_KEY_ID} and the secret key from
 ** {@code <secretRef>_SECRET_ACCESS_KEY}. For kof22, secretRef {@code CDN_AWS}
 ** reads {@code CDN_AWS_ACCESS_KEY_ID} and {@code CDN_AWS_SECRET_ACCESS_KEY}
 ** (design spec section 7.5). Values are never logged or put in messages.</p>
 **
 ** <p>Both clients use region us-east-1 (CloudFront is global and publishes
 ** its CloudWatch metrics there), the JDK URL-connection HTTP client, and no
 ** SDK-level retries: the qbit's purge dispatcher owns backoff, driven by
 ** {@link CdnException#isRetryable()}.</p>
 **
 ** <p>Tests pass an endpoint override and static credentials, or subclass
 ** and override {@link #cloudWatch(CdnProviderConfig)}.</p>
 *******************************************************************************/
public class CloudFrontClientFactory
{
   /** Suffix appended to secretRef for the access key id variable. */
   public static final String ACCESS_KEY_ID_SUFFIX = "_ACCESS_KEY_ID";

   /** Suffix appended to secretRef for the secret access key variable. */
   public static final String SECRET_ACCESS_KEY_SUFFIX = "_SECRET_ACCESS_KEY";

   private final Function<String, String> environment;
   private final URI                      endpointOverride;
   private final AwsCredentialsProvider   credentialsOverride;



   /*******************************************************************************
    ** Production factory: credentials from process environment variables.
    *******************************************************************************/
   public CloudFrontClientFactory()
   {
      this(System::getenv, null, null);
   }



   /*******************************************************************************
    ** Test factory: a fixed endpoint and static credentials.
    *******************************************************************************/
   public CloudFrontClientFactory(URI endpointOverride, AwsCredentialsProvider credentialsOverride)
   {
      this(System::getenv, endpointOverride, credentialsOverride);
   }



   /*******************************************************************************
    ** Full constructor.
    **
    ** @param environment         variable lookup (System::getenv in production)
    ** @param endpointOverride    endpoint for both clients, or null for AWS
    ** @param credentialsOverride credentials to use instead of the environment, or null
    *******************************************************************************/
   public CloudFrontClientFactory(Function<String, String> environment, URI endpointOverride, AwsCredentialsProvider credentialsOverride)
   {
      this.environment = environment;
      this.endpointOverride = endpointOverride;
      this.credentialsOverride = credentialsOverride;
   }



   /*******************************************************************************
    ** A CloudFront client for one provider row.
    *******************************************************************************/
   public CloudFrontClient cloudFront(CdnProviderConfig config) throws CdnException
   {
      CloudFrontClientBuilder builder = CloudFrontClient.builder()
         .region(Region.US_EAST_1)
         .credentialsProvider(credentials(config))
         .httpClientBuilder(UrlConnectionHttpClient.builder())
         .overrideConfiguration(c -> c.retryStrategy(AwsRetryStrategy.doNotRetry()));
      if(endpointOverride != null)
      {
         builder.endpointOverride(endpointOverride);
      }
      return builder.build();
   }



   /*******************************************************************************
    ** A CloudWatch client for one provider row.
    *******************************************************************************/
   public CloudWatchClient cloudWatch(CdnProviderConfig config) throws CdnException
   {
      CloudWatchClientBuilder builder = CloudWatchClient.builder()
         .region(Region.US_EAST_1)
         .credentialsProvider(credentials(config))
         .httpClientBuilder(UrlConnectionHttpClient.builder())
         .overrideConfiguration(c -> c.retryStrategy(AwsRetryStrategy.doNotRetry()));
      if(endpointOverride != null)
      {
         builder.endpointOverride(endpointOverride);
      }
      return builder.build();
   }



   /*******************************************************************************
    ** Resolve credentials: the override, else the secretRef env var pair.
    *******************************************************************************/
   AwsCredentialsProvider credentials(CdnProviderConfig config) throws CdnException
   {
      if(credentialsOverride != null)
      {
         return credentialsOverride;
      }

      String prefix = config.secretRef();
      if(prefix == null || prefix.isBlank())
      {
         throw new CdnException("CDN provider " + config.name() + " has no secretRef (env var prefix for the AWS key)", false, null);
      }

      String accessKeyId = environment.apply(prefix + ACCESS_KEY_ID_SUFFIX);
      String secretAccessKey = environment.apply(prefix + SECRET_ACCESS_KEY_SUFFIX);
      if(accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank())
      {
         throw new CdnException("Environment variables " + prefix + ACCESS_KEY_ID_SUFFIX + " and " + prefix + SECRET_ACCESS_KEY_SUFFIX + " must both be set", false, null);
      }
      return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
   }
}
