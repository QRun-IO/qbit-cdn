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
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.providers.cloudfront.StubHttpServer;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeKind;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Request shapes and error handling of the Souin adapter against an HTTP
 ** stub.
 *******************************************************************************/
class SouinCdnProviderTest
{
   private static final String API = SouinCdnProvider.DEFAULT_API_PATH;

   private StubHttpServer stub;



   /*******************************************************************************
    ** Fresh stub per test.
    *******************************************************************************/
   @BeforeEach
   void startStub() throws Exception
   {
      stub = new StubHttpServer();
   }



   /*******************************************************************************
    ** Stop the stub.
    *******************************************************************************/
   @AfterEach
   void stopStub()
   {
      stub.close();
   }



   /*******************************************************************************
    ** A provider row whose zone field is the stub's bare origin.
    *******************************************************************************/
   private CdnProviderConfig config()
   {
      return new CdnProviderConfig("souin", CdnProviderType.SOUIN, stub.baseUrl(), null, null);
   }



   /*******************************************************************************
    ** Load a souin fixture.
    *******************************************************************************/
   private static String fixture(String name) throws IOException
   {
      try(InputStream in = SouinCdnProviderTest.class.getResourceAsStream("/fixtures/souin/" + name))
      {
         return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
   }



   /*******************************************************************************
    ** Type, capabilities and header dialect.
    *******************************************************************************/
   @Test
   void describesItself()
   {
      SouinCdnProvider souin = new SouinCdnProvider();
      assertThat(souin.getType()).isEqualTo(CdnProviderType.SOUIN);
      assertThat(souin.getCapabilities(config())).containsExactlyInAnyOrder(CdnCapability.PURGE_TAG, CdnCapability.PURGE_URL,
         CdnCapability.HONORS_CDN_CACHE_CONTROL);
      assertThat(souin.getHeaderDialect().tagHeaderName()).isEqualTo("Surrogate-Key");
      assertThat(souin.getHeaderDialect().edgeStripsTagHeader()).isFalse();
      assertThat(souin.getHeaderDialect().honorsCdnCacheControl()).isTrue();
      assertThat(souin.getHeaderDialect().formatTags(List.of("post:1", "blog:7"))).isEqualTo("post:1, blog:7");
   }



   /*******************************************************************************
    ** A tag batch is one PURGE with the Surrogate-Key header.
    *******************************************************************************/
   @Test
   void tagPurge() throws Exception
   {
      stub.stub("PURGE", API, 204, "");

      CdnPurgeResult result = new SouinCdnProvider().purge(config(), new CdnPurgeBatch(CdnPurgeKind.TAG, List.of("post:1", "blog:7"), "r1"));

      assertThat(result.state()).isEqualTo(CdnPurgeResult.State.SUCCEEDED);
      assertThat(result.httpStatus()).isEqualTo(204);
      assertThat(result.message()).isEqualTo("2 TAG item(s) purged");
      assertThat(stub.requests("PURGE", API)).singleElement()
         .satisfies(r -> assertThat(r.header("Surrogate-Key")).isEqualTo("post:1, blog:7"))
         .satisfies(r -> assertThat(r.body()).isEmpty());
   }



   /*******************************************************************************
    ** A paths batch is one POST of a uri-prefix invalidation; the apiUrl
    ** setting wins over the zone field and its path is used as given.
    *******************************************************************************/
   @Test
   void pathPurge() throws Exception
   {
      stub.stub("POST", "/cache-api/souin", 200, "");
      CdnProviderConfig config = new CdnProviderConfig("souin", CdnProviderType.SOUIN, "http://ignored.invalid",
         null, Map.of(SouinCdnProvider.SETTING_API_URL, stub.baseUrl() + "/cache-api/souin"));

      CdnPurgeResult result = new SouinCdnProvider().purge(config, new CdnPurgeBatch(CdnPurgeKind.PATHS,
         List.of("/blog/quick-bits/my-post*", "v1/blog-posts/123*"), "r2"));

      assertThat(result.state()).isEqualTo(CdnPurgeResult.State.SUCCEEDED);
      StubHttpServer.Recorded request = stub.requests("POST", "/cache-api/souin").get(0);
      assertThat(request.header("Content-Type")).isEqualTo("application/json");
      assertThat(new JSONObject(request.body()).similar(new JSONObject(fixture("invalidate-paths-request.json")))).isTrue();
   }



   /*******************************************************************************
    ** 429/5xx and I/O failures are retryable; other statuses are permanent.
    *******************************************************************************/
   @Test
   void failures() throws Exception
   {
      CdnPurgeBatch batch = new CdnPurgeBatch(CdnPurgeKind.TAG, List.of("post:1"), "r");

      stub.stub("PURGE", API, 503, "busy");
      assertThatThrownBy(() -> new SouinCdnProvider().purge(config(), batch))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isTrue())
         .hasMessageContaining("HTTP 503");

      stub.stub("PURGE", API, 429, "");
      assertThatThrownBy(() -> new SouinCdnProvider().purge(config(), batch))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isTrue());

      stub.stub("PURGE", API, 403, "");
      assertThatThrownBy(() -> new SouinCdnProvider().purge(config(), batch))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse());

      int closedPort;
      try(ServerSocket socket = new ServerSocket(0))
      {
         closedPort = socket.getLocalPort();
      }
      CdnProviderConfig unreachable = new CdnProviderConfig("souin", CdnProviderType.SOUIN, "http://127.0.0.1:" + closedPort, null, null);
      assertThatThrownBy(() -> new SouinCdnProvider().purge(unreachable, batch))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isTrue());
   }



   /*******************************************************************************
    ** Unsupported kinds, empty batches and bad URLs.
    *******************************************************************************/
   @Test
   void refusedBatches() throws Exception
   {
      assertThat(new SouinCdnProvider().purge(config(), new CdnPurgeBatch(CdnPurgeKind.TAG, List.of(), "r")).state())
         .isEqualTo(CdnPurgeResult.State.SKIPPED);
      assertThatThrownBy(() -> new SouinCdnProvider().purge(config(), new CdnPurgeBatch(CdnPurgeKind.HOST, List.of("www"), "r")))
         .isInstanceOfSatisfying(CdnException.class, e -> assertThat(e.isRetryable()).isFalse());

      assertThatThrownBy(() -> SouinCdnProvider.apiUri(new CdnProviderConfig("s", CdnProviderType.SOUIN, null, null, null)))
         .hasMessageContaining("no Souin API URL");
      assertThatThrownBy(() -> SouinCdnProvider.apiUri(new CdnProviderConfig("s", CdnProviderType.SOUIN, "souin:80", null, null)))
         .hasMessageContaining("Invalid Souin API URL");
      assertThatThrownBy(() -> SouinCdnProvider.apiUri(new CdnProviderConfig("s", CdnProviderType.SOUIN, "http://bad host/", null, null)))
         .hasMessageContaining("Invalid Souin API URL");
      assertThat(SouinCdnProvider.apiUri(new CdnProviderConfig("s", CdnProviderType.SOUIN, "http://souin:80/", null, null)))
         .hasToString("http://souin:80/souin-api/souin");
      assertThat(SouinCdnProvider.selector("blog/x**")).isEqualTo("/blog/x");
   }
}
