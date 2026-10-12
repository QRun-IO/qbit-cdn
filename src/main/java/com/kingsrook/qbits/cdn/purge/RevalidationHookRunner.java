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

package com.kingsrook.qbits.cdn.purge;


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.cdn.model.CdnTables;
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
 ** Calls every enabled cdn_revalidation_hook before the CDN is purged, so the
 ** edge never refetches a stale page (design spec section 4.5 step 3).
 **
 ** Request: POST {"tags":[...]} with header
 ** "X-Revalidate-Signature: sha256=<hex HMAC-SHA256 of the body>", keyed by
 ** the secret in the environment variable the hook's secretRef names. With
 ** fanOutDns, the hook's host is resolved and every address is called (one
 ** per pod behind a headless Service). Every call must answer 2xx.
 *******************************************************************************/
public class RevalidationHookRunner
{
   public static final String SIGNATURE_HEADER = "X-Revalidate-Signature";

   private static final ObjectMapper JSON = new ObjectMapper();

   private final HttpClient                http;
   private final Function<String, String>  secrets;
   private final Resolver                  resolver;



   /*******************************************************************************
    ** Host name resolution (replaceable in tests).
    *******************************************************************************/
   public interface Resolver
   {
      /*******************************************************************************
       ** All addresses of a host.
       *******************************************************************************/
      List<String> resolve(String host) throws Exception;
   }



   /*******************************************************************************
    ** Production wiring.
    *******************************************************************************/
   public RevalidationHookRunner()
   {
      this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), System::getenv,
         host -> java.util.Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).toList());
   }



   /*******************************************************************************
    ** Constructor with collaborators.
    *******************************************************************************/
   public RevalidationHookRunner(HttpClient http, Function<String, String> secrets, Resolver resolver)
   {
      this.http = http;
      this.secrets = secrets;
      this.resolver = resolver;
   }



   /*******************************************************************************
    ** Call every enabled hook; throws when any call fails.
    *******************************************************************************/
   public int run(List<String> tags) throws QException
   {
      if(tags == null || tags.isEmpty())
      {
         return (0);
      }
      List<QRecord> hooks = new QueryAction().execute(new QueryInput(CdnTables.REVALIDATION_HOOK)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords();
      int calls = 0;
      for(QRecord hook : hooks)
      {
         calls += call(hook, tags);
      }
      return (calls);
   }



   /*******************************************************************************
    ** Call one hook (on every resolved address when fanning out).
    *******************************************************************************/
   int call(QRecord hook, List<String> tags) throws QException
   {
      String name = hook.getValueString("name");
      try
      {
         byte[] body = JSON.writeValueAsBytes(java.util.Map.of("tags", tags));
         String secretRef = hook.getValueString("secretRef");
         String secret = StringUtils.hasContent(secretRef) ? secrets.apply(secretRef) : null;
         URI uri = URI.create(hook.getValueString("url"));
         List<URI> targets = new ArrayList<>();
         if(Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(hook.getValue("fanOutDns"))))
         {
            for(String address : resolver.resolve(uri.getHost()))
            {
               String host = address.contains(":") ? "[" + address + "]" : address;
               targets.add(new URI(uri.getScheme(), null, host, uri.getPort(), uri.getPath(), uri.getQuery(), null));
            }
         }
         else
         {
            targets.add(uri);
         }
         if(targets.isEmpty())
         {
            throw (new QException("Revalidation hook " + name + " resolved to no addresses"));
         }
         Integer timeout = ValueUtils.getValueAsInteger(hook.getValue("timeoutMs"));
         for(URI target : targets)
         {
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
               .timeout(Duration.ofMillis(timeout == null ? 5000 : timeout))
               .header("Content-Type", "application/json")
               .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if(secret != null)
            {
               request.header(SIGNATURE_HEADER, "sha256=" + sign(secret, body));
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if(response.statusCode() / 100 != 2)
            {
               throw (new QException("Revalidation hook " + name + " answered " + response.statusCode() + " from " + target.getHost()));
            }
         }
         return (targets.size());
      }
      catch(QException e)
      {
         throw (e);
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         throw (new QException("Revalidation hook " + name + " was interrupted", e));
      }
      catch(Exception e)
      {
         throw (new QException("Revalidation hook " + name + " failed: " + e.getMessage(), e));
      }
   }



   /*******************************************************************************
    ** Hex HMAC-SHA256.
    *******************************************************************************/
   public static String sign(String secret, byte[] body) throws Exception
   {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return (HexFormat.of().formatHex(mac.doFinal(body)));
   }
}
