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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
               return (stripPort(originHost.strip()));
            }
         }
      }
      return (fallback);
   }



   /*******************************************************************************
    ** The host without a port: "[v6]:443" gives v6, "name:443" gives name, and a bare
    ** IPv6 literal or a plain name is left alone.
    *******************************************************************************/
   static String stripPort(String hostAndPort)
   {
      if(hostAndPort.startsWith("[") && hostAndPort.contains("]"))
      {
         return (hostAndPort.substring(1, hostAndPort.indexOf(']')));
      }
      int colon = hostAndPort.indexOf(':');
      if(colon > 0 && colon == hostAndPort.lastIndexOf(':') && hostAndPort.substring(colon + 1).matches("\\d+"))
      {
         return (hostAndPort.substring(0, colon));
      }
      return (hostAndPort);
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
    ** IPv4 refuses loopback, private, link-local, carrier-grade NAT (100.64.0.0/10),
    ** 0.0.0.0/8, benchmarking (198.18.0.0/15), documentation, multicast and reserved.
    ** IPv6 is an allowlist: only global unicast 2000::/3 passes, minus Teredo
    ** (2001::/32) and documentation (2001:db8::/32); 6to4 (2002::/16) passes only
    ** when its embedded IPv4 is public. The forms that embed an IPv4 address (::/96,
    ** v4-mapped, IPv4-translated, NAT64 64:ff9b::/96) are unwrapped and judged as
    ** IPv4; the local-use NAT64 prefix 64:ff9b:1::/48 is refused.
    *******************************************************************************/
   public static boolean isPublic(InetAddress address)
   {
      byte[] b = address.getAddress();
      if(b.length == 4)
      {
         return (isPublicV4(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff));
      }
      return (isPublicV6(b));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static boolean isPublicV6(byte[] b)
   {
      if(zero(b, 0, 12) || (zero(b, 0, 10) && ff(b, 10)) || (zero(b, 0, 8) && ff(b, 8) && b[10] == 0 && b[11] == 0))
      {
         return (isPublicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff));
      }
      if((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b)
      {
         return (zero(b, 4, 12) && isPublicV4(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff));
      }
      if((b[0] & 0xe0) != 0x20)
      {
         return (false);
      }
      if((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && ((b[2] == 0 && b[3] == 0) || ((b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8)))
      {
         return (false);
      }
      if((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02)
      {
         return (isPublicV4(b[2] & 0xff, b[3] & 0xff, b[4] & 0xff));
      }
      return (true);
   }



   /*******************************************************************************
    ** Whether bytes from (inclusive) to (exclusive) are all zero.
    *******************************************************************************/
   private static boolean zero(byte[] b, int from, int to)
   {
      for(int i = from; i < to; i++)
      {
         if(b[i] != 0)
         {
            return (false);
         }
      }
      return (true);
   }



   /*******************************************************************************
    ** Whether two bytes at an index are ff ff.
    *******************************************************************************/
   private static boolean ff(byte[] b, int at)
   {
      return ((b[at] & 0xff) == 0xff && (b[at + 1] & 0xff) == 0xff);
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
      private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable ->
      {
         Thread thread = new Thread(runnable, "cdn-origin-check-watchdog");
         thread.setDaemon(true);
         return (thread);
      });



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Response fetch(String host, String path) throws IOException
      {
         InetAddress address = pick(InetAddress.getAllByName(host));
         return (fetch(address, 443, host, path, Duration.ofSeconds(10)));
      }



      /*******************************************************************************
       ** Fetch from one checked address within one hard deadline that covers connect,
       ** the TLS handshake and the reads: at the deadline the socket is closed, which
       ** fails whatever read or write is blocked.
       *******************************************************************************/
      Response fetch(InetAddress address, int port, String host, String path, Duration limit) throws IOException
      {
         long deadline = System.nanoTime() + limit.toNanos();
         try(Socket plain = new Socket())
         {
            ScheduledFuture<?> guard = WATCHDOG.schedule(() ->
            {
               try
               {
                  plain.close();
               }
               catch(IOException e)
               {
                  // already closed
               }
            }, limit.toMillis(), TimeUnit.MILLISECONDS);
            try
            {
               plain.connect(new InetSocketAddress(address, port), (int) Math.min(5000, limit.toMillis()));
               plain.setSoTimeout(5000);
               SSLSocket     socket     = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
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
            finally
            {
               guard.cancel(false);
            }
         }
      }
   }
}
