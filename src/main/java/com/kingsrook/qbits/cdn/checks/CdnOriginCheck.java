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


import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
    ** Whether an address is one an origin check may connect to: public unicast only.
    ** Refuses loopback, private, link-local, carrier-grade NAT (100.64.0.0/10),
    ** 0.0.0.0/8, benchmarking (198.18.0.0/15), multicast and reserved IPv4; IPv6
    ** unique-local (fc00::/7), link-local, multicast; and IPv6 forms that embed an
    ** IPv4 address (v4-mapped and -compatible, NAT64 64:ff9b::/96, 6to4), which are
    ** unwrapped and checked as IPv4.
    *******************************************************************************/
   public static boolean isPublic(InetAddress address)
   {
      byte[] b = address.getAddress();
      if(b.length == 4)
      {
         return (isPublicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff));
      }
      boolean zeros12 = true;
      for(int i = 0; i < 12; i++)
      {
         zeros12 &= b[i] == 0;
      }
      boolean mapped = true;
      for(int i = 0; i < 10; i++)
      {
         mapped &= b[i] == 0;
      }
      mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
      boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
      for(int i = 4; i < 12 && nat64; i++)
      {
         nat64 &= b[i] == 0;
      }
      if(zeros12 || mapped || nat64)
      {
         return (isPublicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff));
      }
      if((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02 && !isPublicV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff))
      {
         return (false);
      }
      int     first     = b[0] & 0xff;
      boolean ula       = (first & 0xfe) == 0xfc;
      boolean linkLocal = first == 0xfe && (b[1] & 0xc0) == 0x80;
      boolean siteLocal = first == 0xfe && (b[1] & 0xc0) == 0xc0;
      return (!ula && !linkLocal && !siteLocal && first != 0xff);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean isPublicV4(int a, int b, int c)
   {
      if(a == 0 || a == 10 || a == 127 || a >= 224)
      {
         return (false);
      }
      if(a == 100 && b >= 64 && b <= 127)
      {
         return (false);
      }
      if(a == 169 && b == 254)
      {
         return (false);
      }
      if(a == 172 && b >= 16 && b <= 31)
      {
         return (false);
      }
      if(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
      {
         return (false);
      }
      if(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
      {
         return (false);
      }
      return (!(a == 203 && b == 0 && c == 113));
   }



   /*******************************************************************************
    ** The address to connect to: every address the name resolves to must be public,
    ** and the first one is used, so the connection goes to what was checked.
    *******************************************************************************/
   static InetAddress pick(InetAddress[] resolved) throws IOException
   {
      if(resolved == null || resolved.length == 0)
      {
         throw (new IOException("the host did not resolve"));
      }
      for(InetAddress address : resolved)
      {
         if(!isPublic(address))
         {
            throw (new IOException("the host resolves to a non-public address"));
         }
      }
      return (resolved[0]);
   }



   /*******************************************************************************
    ** Read a status line and headers (and nothing else) from an HTTP/1.1 response,
    ** within a total deadline and a size limit.
    *******************************************************************************/
   static Response readResponse(InputStream in, long deadlineNanos) throws IOException
   {
      String status = readLine(in, deadlineNanos);
      int    total  = status.length();
      String[] parts = status.split(" ", 3);
      if(parts.length < 2 || !parts[0].startsWith("HTTP/"))
      {
         throw (new IOException("not an HTTP response"));
      }
      int code;
      try
      {
         code = Integer.parseInt(parts[1]);
      }
      catch(NumberFormatException e)
      {
         throw (new IOException("bad status line"));
      }
      Map<String, String> headers = new LinkedHashMap<>();
      while(true)
      {
         String line = readLine(in, deadlineNanos);
         total += line.length();
         if(line.isEmpty())
         {
            return (new Response(code, headers));
         }
         if(total > 16_384)
         {
            throw (new IOException("the headers are too large"));
         }
         int colon = line.indexOf(':');
         if(colon > 0)
         {
            headers.merge(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip(), (x, y) -> x + ", " + y);
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String readLine(InputStream in, long deadlineNanos) throws IOException
   {
      StringBuilder line = new StringBuilder();
      while(true)
      {
         if(System.nanoTime() > deadlineNanos)
         {
            throw (new IOException("the origin was too slow"));
         }
         int c = in.read();
         if(c < 0)
         {
            throw (new IOException("the connection closed early"));
         }
         if(c == '\n')
         {
            return (line.toString().stripTrailing());
         }
         line.append((char) c);
         if(line.length() > 8192)
         {
            throw (new IOException("a header line is too long"));
         }
      }
   }



   /*******************************************************************************
    ** The real fetcher: resolve once, refuse non-public addresses, connect to the
    ** address that was checked (TLS with the name as SNI and for verification), send
    ** one GET, read the status line and headers only, and close. No redirects, no body.
    *******************************************************************************/
   static final class HttpFetcher implements Fetcher
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Response fetch(String host, String path) throws IOException
      {
         InetAddress address  = pick(InetAddress.getAllByName(host));
         long        deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
         try(Socket plain = new Socket())
         {
            plain.connect(new InetSocketAddress(address, 443), 5000);
            plain.setSoTimeout(5000);
            SSLSocket     socket     = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, 443, true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(host)));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: qbit-cdn-origin-check\r\nAccept: */*\r\nConnection: close\r\n\r\n")
               .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return (readResponse(new BufferedInputStream(socket.getInputStream()), deadline));
         }
      }
   }
}
