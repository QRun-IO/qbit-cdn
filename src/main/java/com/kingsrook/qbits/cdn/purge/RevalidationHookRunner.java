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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.cdn.model.CdnSignatureFormat;
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
import com.kingsrook.qqq.backend.core.utils.http.SafeHttpClient;
import com.kingsrook.qqq.backend.core.utils.http.SafeHttpPolicy;
import com.kingsrook.qqq.backend.core.utils.webhooks.WebhookSecret;
import com.kingsrook.qqq.backend.core.utils.webhooks.WebhookSigner;


/*******************************************************************************
 ** Calls every enabled cdn_revalidation_hook before the CDN is purged, so the
 ** edge never refetches a stale page (design spec section 4.5 step 3).
 **
 ** Request: POST {"tags":[...]} signed with core Standard Webhooks headers.
 ** LEGACY keeps X-Revalidate-Signature with a hex HMAC of the body. The
 ** secretRef names an environment variable. SafeHttpClient checks and pins
 ** every address; fan-out preserves the original Host and TLS server name.
 *******************************************************************************/
public class RevalidationHookRunner
{
   public static final String SIGNATURE_HEADER = "X-Revalidate-Signature";

   private static final ObjectMapper JSON = new ObjectMapper();

   private final Function<String, String> secrets;
   private final Resolver                 resolver;



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
      this(System::getenv,
         host -> java.util.Arrays.stream(InetAddress.getAllByName(host)).map(InetAddress::getHostAddress).toList());
   }



   /*******************************************************************************
    ** Constructor with collaborators.
    *******************************************************************************/
   public RevalidationHookRunner(Function<String, String> secrets, Resolver resolver)
   {
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
         String formatValue = hook.getValueString("signatureFormat");
         CdnSignatureFormat format = formatValue == null ? CdnSignatureFormat.LEGACY : CdnSignatureFormat.valueOf(formatValue);
         WebhookSecret signingSecret = secret != null && format == CdnSignatureFormat.STANDARD_WEBHOOKS ? WebhookSecret.parse(secret) : null;
         String messageId = UUID.randomUUID().toString();
         URI uri = URI.create(hook.getValueString("url"));
         Integer timeout = ValueUtils.getValueAsInteger(hook.getValue("timeoutMs"));
         if(timeout != null && timeout <= 0)
         {
            throw (new QException("Revalidation hook timeoutMs must be positive"));
         }
         String allowlist = hook.getValueString("internalAllowlist");
         SafeHttpPolicy policy = SafeHttpPolicy.defaults()
            .withAllowPlainHttp(Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(hook.getValue("allowPlainHttp"))))
            .withInternalAllowlist(allowlist == null ? List.of() : Arrays.asList(allowlist.split("[,\\s]+")))
            .withTotalTimeout(Duration.ofMillis(timeout == null ? 5000 : timeout));
         List<String> resolved = resolver.resolve(uri.getHost());
         InetAddress[] addresses = new InetAddress[resolved.size()];
         for(int i = 0; i < resolved.size(); i++)
         {
            addresses[i] = InetAddress.getByName(resolved.get(i));
         }
         if(addresses.length == 0)
         {
            throw (new QException("Revalidation hook " + name + " resolved to no addresses"));
         }
         try(SafeHttpClient validator = new SafeHttpClient(policy, host -> addresses))
         {
            validator.checkUrl(uri.toString());
         }
         boolean fanOut = Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(hook.getValue("fanOutDns")));
         int calls = fanOut ? addresses.length : 1;
         for(int i = 0; i < calls; i++)
         {
            InetAddress[] targetAddresses = fanOut ? new InetAddress[] {addresses[i]} : addresses;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            if(secret != null)
            {
               if(format == CdnSignatureFormat.LEGACY)
               {
                  headers.put(SIGNATURE_HEADER, "sha256=" + sign(secret, body));
               }
               else
               {
                  headers.putAll(WebhookSigner.headers(messageId, Instant.now(), body, List.of(signingSecret)));
               }
            }
            try(SafeHttpClient client = new SafeHttpClient(policy, host -> targetAddresses))
            {
               int status = client.post(uri.toString(), headers, body).statusCode();
               if(status / 100 != 2)
               {
                  throw (new QException("Revalidation hook " + name + " answered " + status + " from " + uri.getHost()));
               }
            }
         }
         return (calls);
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
    ** Legacy compatibility: hex HMAC-SHA256 over the body with raw UTF-8 key bytes.
    *******************************************************************************/
   public static String sign(String secret, byte[] body) throws Exception
   {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return (HexFormat.of().formatHex(mac.doFinal(body)));
   }
}
