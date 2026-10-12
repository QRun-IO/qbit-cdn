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


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.awssdk.services.cloudfront.model.CacheBehavior;
import software.amazon.awssdk.services.cloudfront.model.CustomOriginConfig;
import software.amazon.awssdk.services.cloudfront.model.DistributionConfig;
import software.amazon.awssdk.services.cloudfront.model.Origin;
import software.amazon.awssdk.services.cloudfront.model.OriginCustomHeader;


/*******************************************************************************
 ** The facts of a CloudFront distribution config that drift checks compare.
 ** Origin custom header <b>names</b> only: their values are secrets (the
 ** X-Origin-Verify header) and are never copied out of the SDK response.
 **
 ** @param enabled              whether the distribution is enabled
 ** @param aliases              alternate domain names (CNAMEs), sorted
 ** @param priceClass           price class, such as PriceClass_All
 ** @param httpVersion          http2, http2and3, ...
 ** @param originDomain         domain of the default behavior's origin
 ** @param originPort           the port CloudFront connects to on that origin
 ** @param originProtocolPolicy https-only, http-only or match-viewer
 ** @param originHeaderNames    custom header names on that origin, sorted
 ** @param behaviors            ordered behaviors, the default one last as "*"
 *******************************************************************************/
public record CloudFrontConfigSnapshot(boolean enabled, List<String> aliases, String priceClass, String httpVersion, String originDomain,
                                       Integer originPort, String originProtocolPolicy, List<String> originHeaderNames, List<Behavior> behaviors)
{
   /** Path pattern used for the default cache behavior. */
   public static final String DEFAULT_PATH_PATTERN = "*";



   /*******************************************************************************
    ** One cache behavior.
    **
    ** @param pathPattern    the behavior's path pattern ("*" for the default)
    ** @param cachePolicyId  its cache policy id (null for legacy forwarded values)
    ** @param targetOriginId the origin it routes to
    *******************************************************************************/
   public record Behavior(String pathPattern, String cachePolicyId, String targetOriginId)
   {
   }



   /*******************************************************************************
    ** Validating constructor; lists are copied.
    *******************************************************************************/
   public CloudFrontConfigSnapshot
   {
      aliases = List.copyOf(aliases);
      originHeaderNames = List.copyOf(originHeaderNames);
      behaviors = List.copyOf(behaviors);
   }



   /*******************************************************************************
    ** Build a snapshot from an SDK DistributionConfig.
    *******************************************************************************/
   public static CloudFrontConfigSnapshot from(DistributionConfig config)
   {
      List<String> aliases = new ArrayList<>();
      if(config.aliases() != null)
      {
         aliases.addAll(config.aliases().items());
      }
      aliases.sort(String::compareTo);

      List<Behavior> behaviors = new ArrayList<>();
      if(config.cacheBehaviors() != null)
      {
         for(CacheBehavior behavior : config.cacheBehaviors().items())
         {
            behaviors.add(new Behavior(behavior.pathPattern(), behavior.cachePolicyId(), behavior.targetOriginId()));
         }
      }

      String defaultOriginId = null;
      if(config.defaultCacheBehavior() != null)
      {
         defaultOriginId = config.defaultCacheBehavior().targetOriginId();
         behaviors.add(new Behavior(DEFAULT_PATH_PATTERN, config.defaultCacheBehavior().cachePolicyId(), defaultOriginId));
      }

      Origin origin = findOrigin(config, defaultOriginId);
      String originDomain = null;
      Integer originPort = null;
      String protocolPolicy = null;
      List<String> headerNames = new ArrayList<>();
      if(origin != null)
      {
         originDomain = origin.domainName();
         CustomOriginConfig custom = origin.customOriginConfig();
         if(custom != null)
         {
            protocolPolicy = custom.originProtocolPolicyAsString();
            originPort = "http-only".equals(protocolPolicy) ? custom.httpPort() : custom.httpsPort();
         }
         if(origin.customHeaders() != null)
         {
            for(OriginCustomHeader header : origin.customHeaders().items())
            {
               headerNames.add(header.headerName());
            }
         }
      }
      headerNames.sort(String.CASE_INSENSITIVE_ORDER);

      return new CloudFrontConfigSnapshot(Boolean.TRUE.equals(config.enabled()), aliases, config.priceClassAsString(), config.httpVersionAsString(),
         originDomain, originPort, protocolPolicy, headerNames, behaviors);
   }



   /*******************************************************************************
    ** The origin with the given id, else the first origin, else null.
    *******************************************************************************/
   private static Origin findOrigin(DistributionConfig config, String originId)
   {
      if(config.origins() == null || config.origins().items().isEmpty())
      {
         return null;
      }
      for(Origin origin : config.origins().items())
      {
         if(origin.id().equals(originId))
         {
            return origin;
         }
      }
      return config.origins().items().get(0);
   }



   /*******************************************************************************
    ** The behavior with exactly this path pattern.
    *******************************************************************************/
   public Optional<Behavior> behavior(String pathPattern)
   {
      return behaviors.stream().filter(b -> b.pathPattern().equals(pathPattern)).findFirst();
   }



   /*******************************************************************************
    ** Whether the origin has a custom header of this name (case-insensitive).
    *******************************************************************************/
   public boolean hasOriginHeader(String headerName)
   {
      return originHeaderNames.stream().anyMatch(n -> n.equalsIgnoreCase(headerName));
   }
}
