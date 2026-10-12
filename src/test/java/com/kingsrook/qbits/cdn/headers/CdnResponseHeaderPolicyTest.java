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

package com.kingsrook.qbits.cdn.headers;


import java.util.List;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


/*******************************************************************************
 ** Unit test for CdnResponseHeaderPolicy.
 *******************************************************************************/
class CdnResponseHeaderPolicyTest
{
   private static CdnResponseHeaderPolicy.Request request(String host, String path, boolean authenticated)
   {
      return (new CdnResponseHeaderPolicy.Request(host, path, "API_LEAF", "post", authenticated, false, List.of("post:1"), List.of("site:1")));
   }



   /*******************************************************************************
    ** Matching, priorities, tags per dialect, and the safety rule.
    *******************************************************************************/
   @Test
   void decides() throws Exception
   {
      CdnTestSupport.setUp();
      Integer hostId = (Integer) CdnTestSupport.insert(CdnTables.HOSTNAME, new QRecord().withValue("hostname", "api.example.com").withValue("cdnProviderId", 1)
         .withValue("stage", "OFF")).get(0).getValue("id");
      CdnTestSupport.insert(CdnTables.CACHE_POLICY,
         new QRecord().withValue("name", "leaf").withValue("matchType", "GLOB").withValue("matchValue", "/v1/*/*").withValue("policyClass", "LEAF")
            .withValue("browserMaxAge", 60).withValue("edgeMaxAge", 86400).withValue("staleWhileRevalidate", 60).withValue("staleIfError", 86400)
            .withValue("priority", 10).withValue("enabled", true).withValue("cdnHostnameId", hostId),
         new QRecord().withValue("name", "never").withValue("matchType", "PREFIX").withValue("matchValue", "/v1/me").withValue("policyClass", "NEVER")
            .withValue("noStore", true).withValue("priority", 1).withValue("enabled", true),
         new QRecord().withValue("name", "assets").withValue("matchType", "PREFIX").withValue("matchValue", "/_next/static/").withValue("policyClass", "IMMUTABLE")
            .withValue("browserMaxAge", 31536000).withValue("edgeMaxAge", 31536000).withValue("immutable", true).withValue("priority", 5).withValue("enabled", true),
         new QRecord().withValue("name", "table").withValue("matchType", "TABLE").withValue("matchValue", "post").withValue("policyClass", "HUB")
            .withValue("browserMaxAge", 0).withValue("edgeMaxAge", 60).withValue("priority", 50).withValue("enabled", true),
         new QRecord().withValue("name", "off").withValue("matchType", "PREFIX").withValue("matchValue", "/").withValue("policyClass", "NEVER")
            .withValue("noStore", true).withValue("priority", 0).withValue("enabled", false));

      CdnResponseHeaderPolicy.Decision leaf = CdnResponseHeaderPolicy.decide(request("api.example.com", "/v1/posts/1", false), CdnHeaderDialect.NO_TAGS);
      assertEquals("leaf", leaf.policyName());
      assertEquals("public, max-age=60, s-maxage=86400, stale-while-revalidate=60, stale-if-error=86400", leaf.cacheControl());
      assertNull(leaf.cdnCacheControl());
      assertNull(leaf.tagHeaderName());

      CdnHeaderDialect fastly = new CdnHeaderDialect("Surrogate-Key", " ", false, true);
      CdnResponseHeaderPolicy.Decision tagged = CdnResponseHeaderPolicy.decide(request("api.example.com", "/v1/posts/1", false), fastly);
      assertEquals("max-age=86400, stale-while-revalidate=60, stale-if-error=86400", tagged.cdnCacheControl());
      assertEquals("site:1 post:1", tagged.headers().get("Surrogate-Key"));

      assertEquals("table", CdnResponseHeaderPolicy.decide(request("other.example.com", "/v1/posts/1", false), fastly).policyName());
      assertEquals("public, max-age=31536000, s-maxage=31536000, immutable",
         CdnResponseHeaderPolicy.decide(request("www.example.com", "/_next/static/a.js", false), fastly).cacheControl());
      assertEquals(CdnResponseHeaderPolicy.PRIVATE, CdnResponseHeaderPolicy.decide(request("api.example.com", "/v1/me/posts", false), fastly).cacheControl());
      assertEquals(CdnResponseHeaderPolicy.PRIVATE, CdnResponseHeaderPolicy.decide(request("api.example.com", "/v1/posts/1", true), fastly).cacheControl());
      assertThat(CdnResponseHeaderPolicy.decide(List.of(), request("x", "/", false), fastly).headers()).isEmpty();
      assertEquals(1, CdnResponseHeaderPolicy.current().stream().filter(p -> p.name().equals("leaf")).count());
   }
}
