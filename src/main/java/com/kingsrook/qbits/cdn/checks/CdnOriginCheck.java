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

package com.kingsrook.qbits.cdn.checks;


import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.headers.CdnResponseHeaderPolicy;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** The origin header check (design spec section 3.2): requests a few sample paths
 ** of a hostname from its origin and records whether each answers with the
 ** Cache-Control the cache policies say it should. One cdn_origin_check row is
 ** written per hostname and run: objects checked, correct, missing or wrong, and
 ** unreadable (the request failed, or the origin answered 4xx or 5xx).
 **
 ** The sample paths are "/", the path of every enabled PREFIX policy that applies
 ** to the hostname, and the literal (placeholder-free) invalidation paths of the
 ** enabled tag rules, up to MAX_PATHS. The origin is the provider's originHost when
 ** it has one, otherwise the hostname itself. Requests are HTTPS GETs, never follow
 ** redirects, and refuse hosts that resolve to private, loopback or link-local
 ** addresses.
 **
 ** A response is correct when it has a Cache-Control header and, when an enabled
 ** policy matches the path, the header equals what the policy would send (compared
 ** ignoring case and spaces). With no enabled policy for the path, any Cache-Control
 ** header is correct: the check proves the origin sets one at all.
 *******************************************************************************/
public final class CdnOriginCheck
{
   public static final int MAX_PATHS = 20;

   private static volatile Fetcher fetcher = new HttpFetcher();



   /*******************************************************************************
    ** One origin answer: status and lower-cased header names.
    *******************************************************************************/
   public record Response(int status, Map<String, String> headers)
   {
   }



   /*******************************************************************************
    ** How the origin is asked (replaceable in tests).
    *******************************************************************************/
   public interface Fetcher
   {
      /*******************************************************************************
       ** GET https://host/path; throws when the origin cannot be read at all.
       *******************************************************************************/
      Response fetch(String host, String path) throws IOException;
   }



   /*******************************************************************************
    ** The outcome of checking one hostname.
    *******************************************************************************/
   public record Result(String scope, int checked, int correct, int wrong, int unreadable, List<String> problems)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnOriginCheck()
   {
   }



   /*******************************************************************************
    ** Replace how origins are asked; null restores the real HTTP fetcher.
    *******************************************************************************/
   public static void setFetcher(Fetcher newFetcher)
   {
      fetcher = newFetcher == null ? new HttpFetcher() : newFetcher;
   }



   /*******************************************************************************
    ** The sample paths of a hostname.
    *******************************************************************************/
   public static List<String> samplePaths(String hostname) throws QException
   {
      Set<String> paths = new LinkedHashSet<>();
      paths.add("/");
      for(CdnResponseHeaderPolicy.Policy policy : CdnResponseHeaderPolicy.current())
      {
         if("PREFIX".equals(policy.matchType()) && policy.matchValue().startsWith("/") && (policy.hostname() == null || policy.hostname().equalsIgnoreCase(hostname)))
         {
            paths.add(policy.matchValue());
         }
      }
      for(QRecord rule : new QueryAction().execute(new QueryInput(CdnTables.TABLE_TAG_RULE)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords())
      {
         String text = rule.getValueString("invalidationPaths");
         for(String line : text == null ? new String[0] : text.split("\\R"))
         {
            String path = line.strip();
            if(path.startsWith("/") && !path.contains("{") && !path.contains("*"))
            {
               paths.add(path);
            }
         }
      }
      return (new ArrayList<>(paths).subList(0, Math.min(MAX_PATHS, paths.size())));
   }



   /*******************************************************************************
    ** Check one hostname row (and record nothing; see record()).
    *******************************************************************************/
   public static Result check(QRecord hostname) throws QException
   {
      String name   = hostname.getValueString("hostname");
      String origin = originOf(hostname, name);
      int    correct = 0;
      int    wrong   = 0;
      int    unread  = 0;
      List<String> problems = new ArrayList<>();
      List<String> paths    = samplePaths(name);
      for(String path : paths)
      {
         Response response;
         try
         {
            response = fetcher.fetch(origin, path);
         }
         catch(IOException | RuntimeException e)
         {
            unread++;
            problems.add(path + ": could not be read (" + e.getMessage() + ")");
            continue;
         }
         if(response.status() >= 400)
         {
            unread++;
            problems.add(path + ": the origin answered " + response.status());
            continue;
         }
         String actual = response.headers().get("cache-control");
         if(!StringUtils.hasContent(actual))
         {
            wrong++;
            problems.add(path + ": no Cache-Control header");
            continue;
         }
         CdnResponseHeaderPolicy.Decision decision = CdnResponseHeaderPolicy.decide(
            new CdnResponseHeaderPolicy.Request(name, path, null, null, false, false, List.of(), List.of()), null);
         if(decision.policyName() != null && !normalize(decision.cacheControl()).equals(normalize(actual)))
         {
            wrong++;
            problems.add(path + ": Cache-Control is \"" + actual + "\" but the policy " + decision.policyName() + " says \"" + decision.cacheControl() + "\"");
         }
         else
         {
            correct++;
         }
      }
      return (new Result(name, paths.size(), correct, wrong, unread, problems));
   }



   /*******************************************************************************
    ** Check a hostname and write its cdn_origin_check row; returns the result.
    *******************************************************************************/
   public static Result checkAndRecord(QRecord hostname) throws QException
   {
      Result result = check(hostname);
      new InsertAction().execute(new InsertInput(CdnTables.ORIGIN_CHECK).withRecord(new QRecord()
         .withValue("checkDate", CdnRuntime.clock().instant())
         .withValue("scope", result.scope())
         .withValue("objectsChecked", result.checked())
         .withValue("correct", result.correct())
         .withValue("missingOrWrong", result.wrong())
         .withValue("unreadable", result.unreadable())));
      return (result);
   }



   /*******************************************************************************
    ** The host to ask: the provider's origin host, else the hostname.
    *******************************************************************************/
   private static String originOf(QRecord hostname, String fallback) throws QException
   {
      java.io.Serializable providerId = hostname.getValue("cdnProviderId");
      if(providerId != null)
      {
         for(QRecord provider : new QueryAction().execute(new QueryInput(CdnTables.PROVIDER)
            .withFilter(new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, providerId)))).getRecords())
         {
            String originHost = provider.getValueString("originHost");
            if(StringUtils.hasContent(originHost))
            {
               return (originHost.strip().replaceFirst(":\\d+$", ""));
            }
         }
      }
      return (fallback);
   }



   /*******************************************************************************
    ** Lower case, no spaces.
    *******************************************************************************/
   private static String normalize(String cacheControl)
   {
      return (cacheControl == null ? "" : cacheControl.toLowerCase(Locale.ROOT).replace(" ", ""));
   }



   /*******************************************************************************
    ** The real fetcher: HTTPS GET, no redirects, public addresses only.
    *******************************************************************************/
   static final class HttpFetcher implements Fetcher
   {
      private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Response fetch(String host, String path) throws IOException
      {
         for(InetAddress address : InetAddress.getAllByName(host))
         {
            if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())
            {
               throw (new IOException("the host resolves to a non-public address"));
            }
         }
         try
         {
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create("https://" + host + path)).timeout(Duration.ofSeconds(10)).GET().build(),
               HttpResponse.BodyHandlers.discarding());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((key, values) -> headers.put(key.toLowerCase(Locale.ROOT), String.join(", ", values)));
            return (new Response(response.statusCode(), headers));
         }
         catch(InterruptedException e)
         {
            Thread.currentThread().interrupt();
            throw (new IOException("interrupted", e));
         }
      }
   }
}
