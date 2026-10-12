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


import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Document;


/*******************************************************************************
 ** A minimal HTTP stub on the JDK's built-in server, standing in for WireMock.
 **
 ** <p>Why not WireMock: the QQQ 4.1 BOM manages Jetty to 12.x, while WireMock
 ** 3.13.2's default server needs Jetty 11, so WireMock fails at startup with
 ** "Jetty 11 is not present". Fixing that needs a pom change (for example
 ** org.wiremock:wiremock-jetty12), which is out of scope here.</p>
 **
 ** <p>Stubs match on method and path (query ignored); the last stub for a
 ** method and path wins. Unmatched requests get 404. Every request is
 ** recorded.</p>
 *******************************************************************************/
public final class StubHttpServer implements AutoCloseable
{
   private final HttpServer                    server;
   private final Map<String, Stub>             stubs    = new ConcurrentHashMap<>();
   private final List<Recorded>                requests = new CopyOnWriteArrayList<>();



   /*******************************************************************************
    ** A canned response.
    **
    ** @param status  HTTP status
    ** @param body    response body (may be empty)
    ** @param headers response headers
    *******************************************************************************/
   public record Stub(int status, String body, Map<String, String> headers)
   {
   }



   /*******************************************************************************
    ** A received request.
    **
    ** @param method  HTTP method
    ** @param uri     path and query
    ** @param headers request headers
    ** @param body    request body
    *******************************************************************************/
   public record Recorded(String method, String uri, Headers headers, String body)
   {
      /*******************************************************************************
       ** First value of a request header, or null.
       *******************************************************************************/
      public String header(String name)
      {
         return headers.getFirst(name);
      }



      /*******************************************************************************
       ** Evaluate an XPath expression (use local-name() for namespaced XML)
       ** against the body.
       *******************************************************************************/
      public String xpath(String expression)
      {
         try
         {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            return XPathFactory.newInstance().newXPath().evaluate(expression, document);
         }
         catch(Exception e)
         {
            throw new IllegalStateException("Body is not XML: " + body, e);
         }
      }
   }



   /*******************************************************************************
    ** Start on a free loopback port.
    *******************************************************************************/
   public StubHttpServer() throws IOException
   {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::handle);
      server.start();
   }



   /*******************************************************************************
    ** http://127.0.0.1:port
    *******************************************************************************/
   public String baseUrl()
   {
      return "http://127.0.0.1:" + server.getAddress().getPort();
   }



   /*******************************************************************************
    ** Register (or replace) a stub.
    *******************************************************************************/
   public void stub(String method, String path, Stub stub)
   {
      stubs.put(method + " " + path, stub);
   }



   /*******************************************************************************
    ** Register a stub with a body and no extra headers.
    *******************************************************************************/
   public void stub(String method, String path, int status, String body)
   {
      stub(method, path, new Stub(status, body, Map.of()));
   }



   /*******************************************************************************
    ** Requests received for a method and path (query ignored).
    *******************************************************************************/
   public List<Recorded> requests(String method, String path)
   {
      return requests.stream().filter(r -> r.method().equals(method) && r.uri().split("\\?", 2)[0].equals(path)).toList();
   }



   /*******************************************************************************
    ** Answer one exchange.
    *******************************************************************************/
   private void handle(HttpExchange exchange) throws IOException
   {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String uri = exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
      requests.add(new Recorded(exchange.getRequestMethod(), uri, exchange.getRequestHeaders(), body));

      Stub stub = stubs.getOrDefault(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath(), new Stub(404, "", Map.of()));
      stub.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
      byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(stub.status(), bytes.length == 0 ? -1 : bytes.length);
      if(bytes.length > 0)
      {
         try(OutputStream out = exchange.getResponseBody())
         {
            out.write(bytes);
         }
      }
      exchange.close();
   }



   /*******************************************************************************
    ** Stop the server.
    *******************************************************************************/
   @Override
   public void close()
   {
      server.stop(0);
   }
}
