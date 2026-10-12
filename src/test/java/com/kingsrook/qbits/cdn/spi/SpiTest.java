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

package com.kingsrook.qbits.cdn.spi;


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.providers.NoneCdnProvider;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Unit tests for the SPI value types and the NONE provider.
 *******************************************************************************/
class SpiTest
{
   /*******************************************************************************
    ** The NONE provider never sends anything and emits no tags.
    *******************************************************************************/
   @Test
   void noneProvider() throws Exception
   {
      NoneCdnProvider none = new NoneCdnProvider();
      CdnProviderConfig config = new CdnProviderConfig("none", CdnProviderType.NONE, null, null, null);
      assertThat(none.getType()).isEqualTo(CdnProviderType.NONE);
      assertThat(none.getCapabilities(config)).contains(CdnCapability.PURGE_TAG);
      assertThat(none.getHeaderDialect().emitsTags()).isFalse();

      CdnPurgeResult result = none.purge(config, new CdnPurgeBatch(CdnPurgeKind.PATHS, List.of("/a*", "/b"), "req-1"));
      assertThat(result.state()).isEqualTo(CdnPurgeResult.State.SKIPPED);
      assertThat(result.message()).contains("2 PATHS");
      assertThat(none.checkPurge(config, "x")).isEqualTo(CdnPurgeResult.succeeded("x"));
   }



   /*******************************************************************************
    ** Header dialects format tags only when the provider has a tag header.
    *******************************************************************************/
   @Test
   void headerDialect()
   {
      CdnHeaderDialect fastly = new CdnHeaderDialect("Surrogate-Key", " ", false, false);
      assertThat(fastly.formatTags(List.of("post:1", "blog:7"))).isEqualTo("post:1 blog:7");
      assertThat(fastly.formatTags(List.of())).isNull();
      assertThat(CdnHeaderDialect.NO_TAGS.formatTags(List.of("post:1"))).isNull();
   }



   /*******************************************************************************
    ** Value types validate and copy their inputs.
    *******************************************************************************/
   @Test
   void valueTypes()
   {
      assertThat(new CdnPurgeBatch(CdnPurgeKind.ALL, null, "r").items()).isEmpty();
      assertThatThrownBy(() -> new CdnPurgeBatch(null, List.of(), "r")).isInstanceOf(NullPointerException.class);
      assertThat(new CdnProviderConfig("x", CdnProviderType.BUNNY, "z", "S", Map.of("n", "7")).intSetting("n", 1)).isEqualTo(7);
      assertThat(new CdnProviderConfig("x", CdnProviderType.BUNNY, "z", "S", null).intSetting("n", 1)).isEqualTo(1);

      CdnException retryable = new CdnException("throttled", true, null);
      assertThat(retryable.isRetryable()).isTrue();
      assertThat(CdnCapability.values()).contains(CdnCapability.HONORS_CDN_CACHE_CONTROL);
      assertThat(CdnPurgeKind.valueOf("TAG")).isEqualTo(CdnPurgeKind.TAG);
   }
}
