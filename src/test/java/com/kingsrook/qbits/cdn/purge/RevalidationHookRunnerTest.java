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


import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** Revalidation hooks against a local HTTP server: signed body, fan-out to
 ** every resolved address, and failure on a non-2xx answer.
 *******************************************************************************/
class RevalidationHookRunnerTest
{
   private final List<String>  bodies     = new CopyOnWriteArrayList<>();
   private final List<String>  signatures = new CopyOnWriteArrayList<>();
   private final AtomicInteger status     = new AtomicInteger(200);
   private HttpServer          server;



   /*******************************************************************************
    ** Start the server and the instance.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      CdnTestSupport.setUp();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/api/revalidate", exchange ->
      {
         bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         String signature = exchange.getRequestHeaders().getFirst(RevalidationHookRunner.SIGNATURE_HEADER);
         signatures.add(signature == null ? "none" : signature);
         exchange.sendResponseHeaders(status.get(), -1);
         exchange.close();
      });
      server.start();
   }



   /*******************************************************************************
    ** Stop the server.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      server.stop(0);
   }



   /*******************************************************************************
    ** Every resolved address is called with the signed body.
    *******************************************************************************/
   @Test
   void signsAndFansOut() throws Exception
   {
      CdnTestSupport.insert(CdnTables.REVALIDATION_HOOK, new QRecord().withValue("name", "next").withValue("enabled", true).withValue("fanOutDns", true)
         .withValue("secretRef", "SECRET").withValue("url", "http://frontend.invalid:" + server.getAddress().getPort() + "/api/revalidate"));
      RevalidationHookRunner runner = new RevalidationHookRunner(HttpClient.newHttpClient(), name -> "SECRET".equals(name) ? "s3cret" : null,
         host -> List.of("127.0.0.1", "127.0.0.1"));
      assertEquals(2, runner.run(List.of("post:1")));
      assertThat(bodies).hasSize(2).allMatch("{\"tags\":[\"post:1\"]}"::equals);
      String expected = "sha256=" + RevalidationHookRunner.sign("s3cret", bodies.get(0).getBytes(StandardCharsets.UTF_8));
      assertThat(signatures).allMatch(expected::equals);
      assertEquals(0, runner.run(List.of()));
   }



   /*******************************************************************************
    ** A non-2xx answer or no addresses fails the run.
    *******************************************************************************/
   @Test
   void failures() throws Exception
   {
      status.set(500);
      CdnTestSupport.insert(CdnTables.REVALIDATION_HOOK, new QRecord().withValue("name", "next").withValue("enabled", true)
         .withValue("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/revalidate"));
      RevalidationHookRunner runner = new RevalidationHookRunner(HttpClient.newHttpClient(), name -> null, host -> List.of());
      assertThatThrownBy(() -> runner.run(List.of("post:1"))).isInstanceOf(QException.class).hasMessageContaining("500");
      assertThat(signatures).containsExactly("none");

      QRecord fanOut = new QRecord().withValue("name", "empty").withValue("fanOutDns", true).withValue("url", "http://x.invalid/api/revalidate");
      assertThatThrownBy(() -> runner.call(fanOut, List.of("post:1"))).isInstanceOf(QException.class).hasMessageContaining("no addresses");
   }
}
