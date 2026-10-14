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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.webhooks.WebhookSecret;
import com.kingsrook.qqq.backend.core.utils.webhooks.WebhookVerificationException;
import com.kingsrook.qqq.backend.core.utils.webhooks.WebhookVerifier;
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
   private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();



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
         Map<String, String> received = new LinkedHashMap<>();
         exchange.getRequestHeaders().forEach((key, values) -> received.put(key.toLowerCase(java.util.Locale.ROOT), values.get(0)));
         headers.add(received);
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
         .withValue("secretRef", "SECRET").withValue("allowPlainHttp", true).withValue("internalAllowlist", "frontend.invalid").withValue("url", "http://frontend.invalid:" + server.getAddress().getPort() + "/api/revalidate"));
      RevalidationHookRunner runner = new RevalidationHookRunner(name -> "SECRET".equals(name) ? "test-secret-at-least-16-bytes" : null,
         host -> List.of("127.0.0.1", "127.0.0.1"));
      assertEquals(2, runner.run(List.of("post:1")));
      assertThat(bodies).hasSize(2).allMatch("{\"tags\":[\"post:1\"]}"::equals);
      WebhookVerifier verifier = new WebhookVerifier(List.of(WebhookSecret.parse("test-secret-at-least-16-bytes")));
      for(int i = 0; i < bodies.size(); i++)
      {
         verifier.verify(headers.get(i), bodies.get(i).getBytes(StandardCharsets.UTF_8));
      }
      assertThat(signatures).containsExactly("none", "none");
      assertThat(headers.get(0).get("webhook-id")).isEqualTo(headers.get(1).get("webhook-id"));
      assertThat(headers.get(0).get("host")).startsWith("frontend.invalid:");
      assertThatThrownBy(() -> verifier.verify(headers.get(0), "tampered".getBytes(StandardCharsets.UTF_8)))
         .isInstanceOf(WebhookVerificationException.class);
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
         .withValue("allowPlainHttp", true).withValue("internalAllowlist", "127.0.0.1").withValue("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/revalidate"));
      RevalidationHookRunner runner = new RevalidationHookRunner(name -> null, host -> List.of("127.0.0.1"));
      assertThatThrownBy(() -> runner.run(List.of("post:1"))).isInstanceOf(QException.class).hasMessageContaining("500");
      assertThat(signatures).containsExactly("none");

      QRecord fanOut = new QRecord().withValue("name", "empty").withValue("fanOutDns", true).withValue("url", "http://x.invalid/api/revalidate");
      assertThatThrownBy(() -> new RevalidationHookRunner(name -> null, host -> List.of()).call(fanOut, List.of("post:1"))).isInstanceOf(QException.class).hasMessageContaining("no addresses");
   }



   /*******************************************************************************
    ** Legacy mode preserves fixed signatures, including whitespace and whsec_ keys.
    *******************************************************************************/
   @Test
   void legacyGoldenVectors() throws Exception
   {
      new RevalidationHookRunner(name -> "s3cret", host -> List.of("127.0.0.1"))
         .call(hook().withValue("signatureFormat", "LEGACY"), List.of("post:1"));
      assertThat(signatures.get(0)).isEqualTo("sha256=540457336a45ef962b165f8732f71ebf327ffa67c0cb1047c446df0231fa0db2");
      new RevalidationHookRunner(name -> "  whsec_test-key-with-spaces  ", host -> List.of("127.0.0.1"))
         .call(hook().withValue("signatureFormat", "LEGACY"), List.of("post:1"));
      assertThat(signatures.get(1)).isEqualTo("sha256=a4dd022bffe6f799633102c415fdc18e22f078bfc98357f909b5df58d5318b05");
      new RevalidationHookRunner(name -> "s3cret", host -> List.of("127.0.0.1"))
         .call(hook(), List.of("post:1"));
      assertThat(signatures.get(2)).as("a null format (existing rows) keeps the legacy signature").isEqualTo(signatures.get(0));
      assertThat(bodies).containsExactly("{\"tags\":[\"post:1\"]}", "{\"tags\":[\"post:1\"]}", "{\"tags\":[\"post:1\"]}");
      assertThat(headers).allSatisfy(value -> assertThat(value).doesNotContainKeys("webhook-id", "webhook-timestamp", "webhook-signature"));
   }



   /*******************************************************************************
    ** An explicit standard format accepts encoded secrets.
    *******************************************************************************/
   @Test
   void explicitStandardFormat() throws Exception
   {
      WebhookSecret secret = WebhookSecret.generate();
      new RevalidationHookRunner(name -> secret.reveal(), host -> List.of("127.0.0.1"))
         .call(hook().withValue("signatureFormat", "STANDARD_WEBHOOKS"), List.of("post:1"));
      new WebhookVerifier(List.of(secret)).verify(headers.get(0), bodies.get(0).getBytes(StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    ** Invalid format and short standard secrets fail before sending.
    *******************************************************************************/
   @Test
   void invalidSigningConfiguration()
   {
      RevalidationHookRunner runner = new RevalidationHookRunner(name -> "short", host -> List.of("127.0.0.1"));
      assertThatThrownBy(() -> runner.call(hook().withValue("signatureFormat", "STANDARD_WEBHOOKS"), List.of("post:1"))).isInstanceOf(QException.class).hasMessageContaining("at least 16 bytes");
      assertThatThrownBy(() -> runner.call(hook().withValue("signatureFormat", "UNKNOWN"), List.of("post:1"))).isInstanceOf(QException.class);
      assertThat(bodies).isEmpty();
   }



   /*******************************************************************************
    ** Safe defaults reject HTTP, private addresses and mixed fan-out before sending.
    *******************************************************************************/
   @Test
   void safeHttpPolicy()
   {
      RevalidationHookRunner runner = new RevalidationHookRunner(name -> null, host -> List.of("127.0.0.1"));
      assertThatThrownBy(() -> runner.call(hook().withValue("allowPlainHttp", null), List.of("post:1")))
         .isInstanceOf(QException.class).hasMessageContaining("Only https");
      assertThatThrownBy(() -> runner.call(hook().withValue("internalAllowlist", null), List.of("post:1")))
         .isInstanceOf(QException.class).hasMessageContaining("Blocked");
      RevalidationHookRunner mixed = new RevalidationHookRunner(name -> null, host -> List.of("127.0.0.1", "169.254.169.254"));
      assertThatThrownBy(() -> mixed.call(hook().withValue("fanOutDns", true), List.of("post:1")))
         .isInstanceOf(QException.class).hasMessageContaining("Blocked");
      assertThatThrownBy(() -> runner.call(hook().withValue("timeoutMs", 0), List.of("post:1")))
         .isInstanceOf(QException.class).hasMessageContaining("positive");
      assertThat(bodies).isEmpty();
   }



   /*******************************************************************************
    ** A local test hook explicitly opts into HTTP and loopback.
    *******************************************************************************/
   private QRecord hook()
   {
      return (new QRecord().withValue("name", "test").withValue("secretRef", "SECRET")
         .withValue("allowPlainHttp", true).withValue("internalAllowlist", "127.0.0.1")
         .withValue("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/revalidate"));
   }
}
