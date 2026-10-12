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
import java.util.Objects;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/*******************************************************************************
 ** Compares a live distribution snapshot with the OpenTofu {@code expected_config}
 ** output. The JSON schema is documented in docs/cloudfront-expected-config.md.
 ** Every key is optional: a key that is absent is not checked.
 *******************************************************************************/
public final class CloudFrontDriftCheck
{
   /** The AWS managed "CachingDisabled" cache policy id. */
   public static final String MANAGED_CACHING_DISABLED_POLICY_ID = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";

   static final String MISSING = "(missing)";



   /*******************************************************************************
    ** Not instantiable.
    *******************************************************************************/
   private CloudFrontDriftCheck()
   {
   }



   /*******************************************************************************
    ** Findings, in a stable order; empty when the distribution matches.
    **
    ** @throws IllegalArgumentException when expectedJson is not a JSON object
    *******************************************************************************/
   public static List<CloudFrontDriftFinding> compare(CloudFrontConfigSnapshot actual, String expectedJson)
   {
      JSONObject expected;
      try
      {
         expected = new JSONObject(expectedJson);
      }
      catch(JSONException | NullPointerException e)
      {
         throw new IllegalArgumentException("expected_config is not a JSON object", e);
      }

      List<CloudFrontDriftFinding> findings = new ArrayList<>();

      if(expected.has("enabled"))
      {
         check(findings, "enabled", String.valueOf(expected.getBoolean("enabled")), String.valueOf(actual.enabled()));
      }
      if(expected.has("aliases"))
      {
         List<String> want = strings(expected.getJSONArray("aliases"));
         want.sort(String::compareTo);
         check(findings, "aliases", String.join(",", want), String.join(",", actual.aliases()));
      }
      if(expected.has("origin_domain"))
      {
         check(findings, "origin.domain", expected.getString("origin_domain"), actual.originDomain());
      }
      if(expected.has("origin_port"))
      {
         check(findings, "origin.port", String.valueOf(expected.getInt("origin_port")), actual.originPort() == null ? null : String.valueOf(actual.originPort()));
      }
      if(expected.has("origin_protocol_policy"))
      {
         check(findings, "origin.protocol", expected.getString("origin_protocol_policy"), actual.originProtocolPolicy());
      }
      if(expected.has("origin_header_names"))
      {
         for(String name : strings(expected.getJSONArray("origin_header_names")))
         {
            if(!actual.hasOriginHeader(name))
            {
               findings.add(new CloudFrontDriftFinding("origin.header:" + name, "present", MISSING));
            }
         }
      }
      if(expected.has("price_class"))
      {
         check(findings, "price_class", expected.getString("price_class"), actual.priceClass());
      }
      if(expected.has("http_version"))
      {
         check(findings, "http_version", expected.getString("http_version"), actual.httpVersion());
      }
      if(expected.has("behavior_paths"))
      {
         compareBehaviors(findings, actual, strings(expected.getJSONArray("behavior_paths")));
      }
      if(expected.has("never_cache_paths"))
      {
         String disabledId = expected.optString("caching_disabled_policy_id", MANAGED_CACHING_DISABLED_POLICY_ID);
         for(String path : strings(expected.getJSONArray("never_cache_paths")))
         {
            Optional<CloudFrontConfigSnapshot.Behavior> behavior = actual.behavior(path);
            String policy = behavior.map(CloudFrontConfigSnapshot.Behavior::cachePolicyId).orElse(MISSING);
            check(findings, "never_cache:" + path, disabledId, policy);
         }
      }
      return findings;
   }



   /*******************************************************************************
    ** Missing and unexpected behaviors, then their order (CloudFront matches
    ** behaviors in order, so a reorder changes routing).
    *******************************************************************************/
   private static void compareBehaviors(List<CloudFrontDriftFinding> findings, CloudFrontConfigSnapshot actual, List<String> wantPaths)
   {
      List<String> havePaths = actual.behaviors().stream()
         .map(CloudFrontConfigSnapshot.Behavior::pathPattern)
         .filter(p -> !CloudFrontConfigSnapshot.DEFAULT_PATH_PATTERN.equals(p))
         .toList();

      for(String path : wantPaths)
      {
         if(!havePaths.contains(path))
         {
            findings.add(new CloudFrontDriftFinding("behavior:" + path, "present", MISSING));
         }
      }
      for(String path : havePaths)
      {
         if(!wantPaths.contains(path))
         {
            findings.add(new CloudFrontDriftFinding("behavior:" + path, "absent", "present"));
         }
      }

      List<String> wantOrder = wantPaths.stream().filter(havePaths::contains).toList();
      List<String> haveOrder = havePaths.stream().filter(wantPaths::contains).toList();
      if(!wantOrder.equals(haveOrder))
      {
         findings.add(new CloudFrontDriftFinding("behavior_order", String.join(" > ", wantOrder), String.join(" > ", haveOrder)));
      }
   }



   /*******************************************************************************
    ** Add a finding when the values differ.
    *******************************************************************************/
   private static void check(List<CloudFrontDriftFinding> findings, String area, String expected, String actual)
   {
      if(!Objects.equals(expected, actual))
      {
         findings.add(new CloudFrontDriftFinding(area, expected, actual == null ? MISSING : actual));
      }
   }



   /*******************************************************************************
    ** A JSON array of strings as a mutable list.
    *******************************************************************************/
   private static List<String> strings(JSONArray array)
   {
      List<String> result = new ArrayList<>();
      for(int i = 0; i < array.length(); i++)
      {
         result.add(array.getString(i));
      }
      return result;
   }
}
