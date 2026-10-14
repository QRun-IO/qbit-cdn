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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The safety of the origin check's own HTTP code: which addresses it refuses
 ** (every literal is parsed without DNS), that it connects to the address it
 ** checked, and that it reads only a bounded status line and headers.
 *******************************************************************************/
class CdnOriginCheckSafetyTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean isPublic(String literal) throws Exception
   {
      return (CdnOriginCheck.isPublic(InetAddress.getByName(literal)));
   }



   /*******************************************************************************
    ** Public unicast is allowed.
    *******************************************************************************/
   @Test
   void publicAddressesAreAllowed() throws Exception
   {
      for(String literal : List.of("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1", "198.17.0.1", "198.20.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888"))
      {
         assertThat(isPublic(literal)).as(literal).isTrue();
      }
   }



   /*******************************************************************************
    ** Everything non-public is refused, including cloud metadata addresses and the
    ** IPv6 forms that carry an IPv4 address.
    *******************************************************************************/
   @Test
   void nonPublicAddressesAreRefused() throws Exception
   {
      for(String literal : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.100.100.200", "100.127.255.255",
         "0.0.0.0", "0.1.2.3", "198.18.0.1", "198.19.255.255", "224.0.0.1", "255.255.255.255", "192.0.2.1", "203.0.113.9",
         "::1", "::", "fe80::1", "fc00::1", "fd00:ec2::254", "fdff::1", "ff02::1",
         "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1", "::127.0.0.1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:7f00:1::1", "2002:a9fe:a9fe::1"))
      {
         assertThat(isPublic(literal)).as(literal).isFalse();
      }
      assertThat(isPublic("::ffff:8.8.8.8")).as("a mapped public address is judged by its IPv4 part").isTrue();
   }



   /*******************************************************************************
    ** One bad address in a name's answer refuses the whole name, and the address
    ** used is one that was checked (the first).
    *******************************************************************************/
   @Test
   void pickRefusesAnyBadAddressAndReturnsACheckedOne() throws Exception
   {
      InetAddress good = InetAddress.getByName("93.184.216.34");
      assertThat(CdnOriginCheck.pick(new InetAddress[] {good, InetAddress.getByName("8.8.8.8")})).isSameAs(good);
      assertThatThrownBy(() -> CdnOriginCheck.pick(new InetAddress[] {good, InetAddress.getByName("169.254.169.254")})).isInstanceOf(IOException.class).hasMessageContaining("non-public");
      assertThatThrownBy(() -> CdnOriginCheck.pick(new InetAddress[0])).isInstanceOf(IOException.class);
      assertThatThrownBy(() -> new CdnOriginCheck.HttpFetcher().fetch("localhost", "/")).isInstanceOf(IOException.class).hasMessageContaining("non-public");
   }



   /*******************************************************************************
    ** Only the status line and headers are read, names are lower-cased, repeats are
    ** joined, and the body is never touched.
    *******************************************************************************/
   @Test
   void readsOnlyStatusAndHeaders() throws Exception
   {
      InputStream in = new ByteArrayInputStream("HTTP/1.1 200 OK\r\nCache-Control: public, max-age=60\r\nVary: a\r\nVary: b\r\n\r\nBODY".getBytes(StandardCharsets.US_ASCII));
      CdnOriginCheck.Response response = CdnOriginCheck.readResponse(in, System.nanoTime() + 1_000_000_000L);
      assertThat(response.status()).isEqualTo(200);
      assertThat(response.headers()).containsEntry("cache-control", "public, max-age=60").containsEntry("vary", "a, b");
      assertThat(in.read()).as("the body is left unread").isEqualTo('B');
   }



   /*******************************************************************************
    ** Malformed, oversized, truncated and too-slow responses are refused.
    *******************************************************************************/
   @Test
   void refusesBadResponses() throws Exception
   {
      long deadline = System.nanoTime() + 1_000_000_000L;
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream("garbage\r\n\r\n"), deadline)).isInstanceOf(IOException.class);
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream("HTTP/1.1 abc OK\r\n\r\n"), deadline)).isInstanceOf(IOException.class);
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream("HTTP/1.1 200 OK\r\nA: b\r\n"), deadline)).hasMessageContaining("closed early");
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream("HTTP/1.1 200 OK\r\nA: " + "x".repeat(9000) + "\r\n\r\n"), deadline)).hasMessageContaining("too long");
      StringBuilder many = new StringBuilder("HTTP/1.1 200 OK\r\n");
      for(int i = 0; i < 400; i++)
      {
         many.append("H").append(i).append(": ").append("v".repeat(60)).append("\r\n");
      }
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream(many.append("\r\n").toString()), deadline)).hasMessageContaining("too large");
      assertThatThrownBy(() -> CdnOriginCheck.readResponse(stream("HTTP/1.1 200 OK\r\n\r\n"), System.nanoTime() - 1)).hasMessageContaining("too slow");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static InputStream stream(String text)
   {
      return (new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
   }
}
