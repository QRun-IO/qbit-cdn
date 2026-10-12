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


import java.util.Map;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The secretRef credentials convention and client construction (no network).
 *******************************************************************************/
class CloudFrontClientFactoryTest
{
   private static final Map<String, String> ENV = Map.of(
      "CDN_AWS_ACCESS_KEY_ID", "AKIAEXAMPLE",
      "CDN_AWS_SECRET_ACCESS_KEY", "very-secret-value");



   /*******************************************************************************
    ** A config with the given secretRef.
    *******************************************************************************/
   private static CdnProviderConfig config(String secretRef)
   {
      return new CdnProviderConfig("kof22", CdnProviderType.CLOUDFRONT, "E1", secretRef, null);
   }



   /*******************************************************************************
    ** secretRef is a prefix: _ACCESS_KEY_ID and _SECRET_ACCESS_KEY are appended.
    *******************************************************************************/
   @Test
   void readsPrefixedEnvironmentVariables() throws Exception
   {
      CloudFrontClientFactory factory = new CloudFrontClientFactory(ENV::get, null, null);
      AwsCredentials credentials = factory.credentials(config("CDN_AWS")).resolveCredentials();
      assertThat(credentials.accessKeyId()).isEqualTo("AKIAEXAMPLE");
      assertThat(credentials.secretAccessKey()).isEqualTo("very-secret-value");
   }



   /*******************************************************************************
    ** Missing pieces are permanent failures whose messages name variables,
    ** never values.
    *******************************************************************************/
   @Test
   void missingCredentials()
   {
      CloudFrontClientFactory factory = new CloudFrontClientFactory(ENV::get, null, null);
      assertThatThrownBy(() -> factory.credentials(config(null)))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse())
         .hasMessageContaining("no secretRef");
      assertThatThrownBy(() -> factory.credentials(config(" ")))
         .hasMessageContaining("no secretRef");
      assertThatThrownBy(() -> factory.credentials(config("OTHER")))
         .hasMessageContaining("OTHER_ACCESS_KEY_ID")
         .hasMessageContaining("OTHER_SECRET_ACCESS_KEY");

      CloudFrontClientFactory halfSet = new CloudFrontClientFactory(Map.of("CDN_AWS_ACCESS_KEY_ID", "AKIAEXAMPLE", "CDN_AWS_SECRET_ACCESS_KEY", " ")::get, null, null);
      assertThatThrownBy(() -> halfSet.credentials(config("CDN_AWS")))
         .isInstanceOf(CdnException.class)
         .hasMessageNotContaining("AKIAEXAMPLE");

      CloudFrontClientFactory blankKey = new CloudFrontClientFactory(Map.of("CDN_AWS_ACCESS_KEY_ID", "", "CDN_AWS_SECRET_ACCESS_KEY", "x")::get, null, null);
      assertThatThrownBy(() -> blankKey.credentials(config("CDN_AWS"))).isInstanceOf(CdnException.class);
   }



   /*******************************************************************************
    ** Production clients build without touching the network.
    *******************************************************************************/
   @Test
   void buildsProductionClients() throws Exception
   {
      CloudFrontClientFactory factory = new CloudFrontClientFactory(ENV::get, null, null);
      try(CloudFrontClient cloudFront = factory.cloudFront(config("CDN_AWS"));
          CloudWatchClient cloudWatch = factory.cloudWatch(config("CDN_AWS")))
      {
         assertThat(cloudFront.serviceName()).isEqualTo("cloudfront");
         assertThat(cloudWatch.serviceName()).isEqualTo("monitoring");
      }
      assertThat(new CloudFrontClientFactory()).isNotNull();
   }
}
