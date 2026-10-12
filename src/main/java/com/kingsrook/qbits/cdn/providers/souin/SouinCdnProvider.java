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

package com.kingsrook.qbits.cdn.providers.souin;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import org.json.JSONArray;
import org.json.JSONObject;


/*******************************************************************************
 ** Souin (darkweak/souin, the Caddy/Traefik HTTP cache) adapter, used for the
 ** self-hosted cache and the Testcontainers end-to-end tests.
 **
 ** <p>The Souin API endpoint is the setting {@code apiUrl}, else
 ** {@code zoneOrDistributionId}. A bare origin such as
 ** {@code http://souin:80} gets Souin's default path
 ** {@value #DEFAULT_API_PATH} appended; any other path is used as given.</p>
 **
 ** <ul>
 **    <li>TAG batches: {@code PURGE <api>} with header
 **        {@code Surrogate-Key: a, b}. Souin's own surrogate storage splits
 **        the header on ", ", which is also the response tag separator.</li>
 **    <li>PATHS batches: {@code POST <api>} with the JSON invalidation body
 **        {@code {"type":"uri-prefix","selectors":[...],"purge":true}}. A
 **        trailing "*" is dropped (Souin matches the path and anything
 **        under it after "/", "?" or "-"); selectors carry no host, so
 **        they match every host, like CloudFront paths.</li>
 ** </ul>
 **
 ** <p>Purges are synchronous: a 2xx answer is SUCCEEDED. 429 and 5xx and
 ** I/O failures are retryable; other statuses are permanent.</p>
 *******************************************************************************/
public class SouinCdnProvider implements CdnProviderInterface
{
   /** Souin's default API path (api.basepath "/souin-api" + souin.basepath "/souin"). */
   public static final String DEFAULT_API_PATH = "/souin-api/souin";

   /** Setting holding the Souin API URL. */
   public static final String SETTING_API_URL = "apiUrl";

   /** Tag header and separator Souin reads by default. */
   public static final CdnHeaderDialect DIALECT = new CdnHeaderDialect("Surrogate-Key", ", ", false, true);

   private static final Duration TIMEOUT = Duration.ofSeconds(10);

   private final HttpClient httpClient;



   /*******************************************************************************
    ** Constructor with a default JDK HTTP client.
    *******************************************************************************/
   public SouinCdnProvider()
   {
      this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
   }



   /*******************************************************************************
    ** Constructor with a given HTTP client.
    *******************************************************************************/
   public SouinCdnProvider(HttpClient httpClient)
   {
      this.httpClient = httpClient;
   }



   /*******************************************************************************
    ** Getter for type.
    *******************************************************************************/
   @Override
   public CdnProviderType getType()
   {
      return CdnProviderType.SOUIN;
   }



   /*******************************************************************************
    ** Tag and URL purge; Souin honors CDN-Cache-Control.
    *******************************************************************************/
   @Override
   public Set<CdnCapability> getCapabilities(CdnProviderConfig config)
   {
      return Set.of(CdnCapability.PURGE_TAG, CdnCapability.PURGE_URL, CdnCapability.HONORS_CDN_CACHE_CONTROL);
   }



   /*******************************************************************************
    ** Surrogate-Key, comma-space separated, not stripped at the edge.
    *******************************************************************************/
   @Override
   public CdnHeaderDialect getHeaderDialect()
   {
      return DIALECT;
   }



   /*******************************************************************************
    ** Send one TAG or PATHS batch to the Souin API.
    *******************************************************************************/
   @Override
   public CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch) throws CdnException
   {
      if(batch.items().isEmpty())
      {
         return CdnPurgeResult.skipped("Nothing to purge");
      }

      URI api = apiUri(config);
      HttpRequest.Builder request = HttpRequest.newBuilder(api).timeout(TIMEOUT);
      switch(batch.kind())
      {
         case TAG -> request.header("Surrogate-Key", String.join(", ", batch.items()))
            .method("PURGE", HttpRequest.BodyPublishers.noBody());
         case PATHS ->
         {
            JSONArray selectors = new JSONArray();
            batch.items().forEach(p -> selectors.put(selector(p)));
            JSONObject body = new JSONObject().put("type", "uri-prefix").put("selectors", selectors).put("purge", true);
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
         }
         default -> throw new CdnException("Souin adapter cannot purge " + batch.kind() + " batches", false, null);
      }

      HttpResponse<String> response;
      try
      {
         response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      }
      catch(IOException e)
      {
         throw new CdnException("Souin purge failed: " + e.getMessage(), true, e);
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         throw new CdnException("Souin purge interrupted", true, e);
      }

      int status = response.statusCode();
      if(status >= 200 && status < 300)
      {
         return new CdnPurgeResult(CdnPurgeResult.State.SUCCEEDED, null, status, batch.items().size() + " " + batch.kind() + " item(s) purged");
      }
      throw new CdnException("Souin purge failed: HTTP " + status, status == 429 || status >= 500, null);
   }



   /*******************************************************************************
    ** A Souin uri-prefix selector for a planned path: leading "/" ensured,
    ** trailing "*" removed.
    *******************************************************************************/
   static String selector(String path)
   {
      String result = path.startsWith("/") ? path : "/" + path;
      while(result.endsWith("*"))
      {
         result = result.substring(0, result.length() - 1);
      }
      return result;
   }



   /*******************************************************************************
    ** The Souin API endpoint for a provider row.
    *******************************************************************************/
   static URI apiUri(CdnProviderConfig config) throws CdnException
   {
      String url = config.settings().getOrDefault(SETTING_API_URL, config.zoneOrDistributionId());
      if(url == null || url.isBlank())
      {
         throw new CdnException("CDN provider " + config.name() + " has no Souin API URL (setting apiUrl or zoneOrDistributionId)", false, null);
      }

      URI uri;
      try
      {
         uri = URI.create(url.strip());
      }
      catch(IllegalArgumentException e)
      {
         throw new CdnException("Invalid Souin API URL: " + url, false, e);
      }
      if(uri.getScheme() == null || uri.getHost() == null)
      {
         throw new CdnException("Invalid Souin API URL: " + url, false, null);
      }

      String path = uri.getPath();
      if(path == null || path.isEmpty() || "/".equals(path))
      {
         return uri.resolve(DEFAULT_API_PATH);
      }
      return uri;
   }
}
