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


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.tags.CacheTagBudget;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Chooses response cache headers from cdn_cache_policy rows (design spec
 ** sections 3.2 and 4.6). The lowest priority number that matches wins:
 ** - matchType PREFIX: the path starts with matchValue;
 ** - GLOB: the path matches matchValue with * wildcards;
 ** - ROUTE_CLASS: the caller's route class equals matchValue;
 ** - TABLE: the caller's table name equals matchValue.
 ** A policy with a hostname only applies to that hostname.
 **
 ** Safety rule: a response that sets a cookie, or answers an authenticated
 ** request, is always "private, no-store", whatever the policies say.
 *******************************************************************************/
public final class CdnResponseHeaderPolicy
{
   public static final String PRIVATE = "private, no-store";

   private static final Duration CACHE_TTL = Duration.ofSeconds(60);

   private static volatile List<Policy> cached;
   private static volatile Instant      cachedAt = Instant.EPOCH;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnResponseHeaderPolicy()
   {
   }



   /*******************************************************************************
    ** One loaded policy.
    *******************************************************************************/
   public record Policy(String name, String hostname, String matchType, String matchValue, String policyClass, Integer browserMaxAge,
                        Integer edgeMaxAge, Integer staleWhileRevalidate, Integer staleIfError, boolean immutable, boolean noStore, int priority)
   {
   }



   /*******************************************************************************
    ** What to send.
    *******************************************************************************/
   public record Decision(String policyName, String policyClass, String cacheControl, String cdnCacheControl, String tagHeaderName, String tagHeaderValue)
   {
      /*******************************************************************************
       ** The headers as a map (only those with values).
       *******************************************************************************/
      public Map<String, String> headers()
      {
         Map<String, String> headers = new HashMap<>();
         if(cacheControl != null)
         {
            headers.put("Cache-Control", cacheControl);
         }
         if(cdnCacheControl != null)
         {
            headers.put("CDN-Cache-Control", cdnCacheControl);
         }
         if(tagHeaderName != null && tagHeaderValue != null)
         {
            headers.put(tagHeaderName, tagHeaderValue);
         }
         return (headers);
      }
   }



   /*******************************************************************************
    ** What a response is.
    *******************************************************************************/
   public record Request(String host, String path, String routeClass, String tableName, boolean authenticated, boolean setsCookie,
                         List<String> fineTags, List<String> coarseTags)
   {
   }



   /*******************************************************************************
    ** Decide with the current policies (cached for 60 seconds).
    *******************************************************************************/
   public static Decision decide(Request request, CdnHeaderDialect dialect) throws QException
   {
      return (decide(current(), request, dialect));
   }



   /*******************************************************************************
    ** Decide with given policies.
    *******************************************************************************/
   public static Decision decide(List<Policy> policies, Request request, CdnHeaderDialect dialect)
   {
      if(request.authenticated() || request.setsCookie())
      {
         return (new Decision(null, null, PRIVATE, null, null, null));
      }
      Policy policy = policies.stream().filter(candidate -> matches(candidate, request)).min(Comparator.comparingInt(Policy::priority)).orElse(null);
      if(policy == null)
      {
         return (new Decision(null, null, null, null, null, null));
      }
      if(policy.noStore())
      {
         return (new Decision(policy.name(), policy.policyClass(), PRIVATE, null, null, null));
      }
      StringBuilder cacheControl = new StringBuilder("public, max-age=").append(value(policy.browserMaxAge()));
      if(policy.edgeMaxAge() != null)
      {
         cacheControl.append(", s-maxage=").append(policy.edgeMaxAge());
      }
      String stale = stale(policy);
      cacheControl.append(stale);
      if(policy.immutable())
      {
         cacheControl.append(", immutable");
      }
      String cdnCacheControl = dialect != null && dialect.honorsCdnCacheControl() && policy.edgeMaxAge() != null ? "max-age=" + policy.edgeMaxAge() + stale : null;
      String tagName = null;
      String tagValue = null;
      if(dialect != null && dialect.emitsTags())
      {
         tagValue = dialect.formatTags(CacheTagBudget.apply(request.fineTags(), request.coarseTags()));
         tagName = tagValue == null ? null : dialect.tagHeaderName();
      }
      return (new Decision(policy.name(), policy.policyClass(), cacheControl.toString(), cdnCacheControl, tagName, tagValue));
   }



   /*******************************************************************************
    ** The stale-* part.
    *******************************************************************************/
   private static String stale(Policy policy)
   {
      StringBuilder stale = new StringBuilder();
      if(policy.staleWhileRevalidate() != null)
      {
         stale.append(", stale-while-revalidate=").append(policy.staleWhileRevalidate());
      }
      if(policy.staleIfError() != null)
      {
         stale.append(", stale-if-error=").append(policy.staleIfError());
      }
      return (stale.toString());
   }



   /*******************************************************************************
    ** Zero for null.
    *******************************************************************************/
   private static int value(Integer value)
   {
      return (value == null ? 0 : value);
   }



   /*******************************************************************************
    ** Whether a policy applies to a request.
    *******************************************************************************/
   static boolean matches(Policy policy, Request request)
   {
      if(policy.hostname() != null && !policy.hostname().equalsIgnoreCase(request.host()))
      {
         return (false);
      }
      String value = policy.matchValue();
      return (switch(policy.matchType() == null ? "" : policy.matchType())
      {
         case "PREFIX" -> request.path() != null && request.path().startsWith(value);
         case "GLOB" -> request.path() != null && glob(value).matcher(request.path()).matches();
         case "ROUTE_CLASS" -> value.equals(request.routeClass());
         case "TABLE" -> value.equals(request.tableName());
         default -> false;
      });
   }



   /*******************************************************************************
    ** A glob ("*" matches anything) as a regex.
    *******************************************************************************/
   static Pattern glob(String glob)
   {
      StringBuilder regex = new StringBuilder();
      for(String part : glob.split("\\*", -1))
      {
         if(regex.length() > 0)
         {
            regex.append(".*");
         }
         regex.append(Pattern.quote(part));
      }
      return (Pattern.compile(regex.toString()));
   }



   /*******************************************************************************
    ** Enabled policies, reloaded at most every 60 seconds.
    *******************************************************************************/
   public static List<Policy> current() throws QException
   {
      Instant now = CdnRuntime.clock().instant();
      List<Policy> policies = cached;
      if(policies == null || cachedAt.plus(CACHE_TTL).isBefore(now))
      {
         policies = load();
         cached = policies;
         cachedAt = now;
      }
      return (policies);
   }



   /*******************************************************************************
    ** Drop the cache (after policy edits, and in tests).
    *******************************************************************************/
   public static void clearCache()
   {
      cached = null;
   }



   /*******************************************************************************
    ** Load enabled policies with their hostnames.
    *******************************************************************************/
   static List<Policy> load() throws QException
   {
      Map<Integer, String> hostnames = new HashMap<>();
      for(QRecord hostname : new QueryAction().execute(new QueryInput(CdnTables.HOSTNAME)).getRecords())
      {
         hostnames.put(ValueUtils.getValueAsInteger(hostname.getValue("id")), hostname.getValueString("hostname"));
      }
      List<Policy> policies = new ArrayList<>();
      for(QRecord row : new QueryAction().execute(new QueryInput(CdnTables.CACHE_POLICY)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords())
      {
         Integer hostnameId = ValueUtils.getValueAsInteger(row.getValue("cdnHostnameId"));
         Integer priority = ValueUtils.getValueAsInteger(row.getValue("priority"));
         if(!StringUtils.hasContent(row.getValueString("matchValue")))
         {
            continue;
         }
         policies.add(new Policy(row.getValueString("name"), hostnameId == null ? null : hostnames.get(hostnameId), row.getValueString("matchType"),
            row.getValueString("matchValue"), row.getValueString("policyClass"), ValueUtils.getValueAsInteger(row.getValue("browserMaxAge")),
            ValueUtils.getValueAsInteger(row.getValue("edgeMaxAge")), ValueUtils.getValueAsInteger(row.getValue("staleWhileRevalidate")),
            ValueUtils.getValueAsInteger(row.getValue("staleIfError")), Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(row.getValue("immutable"))),
            Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(row.getValue("noStore"))), priority == null ? 100 : priority));
      }
      return (policies);
   }
}
