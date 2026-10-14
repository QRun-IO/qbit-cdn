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

package com.kingsrook.qbits.cdn.tables;


import java.util.List;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.app.CdnSetupStatus;
import com.kingsrook.qbits.cdn.app.CdnSetupStatus.Item;
import com.kingsrook.qbits.cdn.app.CdnSetupStatus.State;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Setup card of the CDN home: empty, on the No CDN default, and fully set up.
 *******************************************************************************/
class CdnSetupStatusTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   private static Item item(List<Item> items, String label)
   {
      return (items.stream().filter(i -> i.label().equals(label)).findFirst().orElseThrow());
   }



   /*******************************************************************************
    ** Nothing set up: the next steps name the Create default actions, and the home shows them.
    *******************************************************************************/
   @Test
   void emptySetupPointsAtTheDefaults() throws Exception
   {
      CdnTestSupport.setUp();
      List<Item> items = CdnSetupStatus.load();
      assertThat(item(items, "Providers").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Providers").nextStep()).contains("Create default provider");
      assertThat(item(items, "Tag rules").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Tag rules").nextStep()).contains("Create default rules");
      assertThat(item(items, "Cache policies").nextStep()).contains("Create default policies");
      assertThat(items).extracting(Item::tableName).contains(CdnTables.PROVIDER, CdnTables.HOSTNAME, CdnTables.CACHE_POLICY, CdnTables.TABLE_TAG_RULE,
         CdnTables.REVALIDATION_HOOK, CdnTables.ORIGIN_CHECK);
   }



   /*******************************************************************************
    ** A real CDN, a Live hostname, a policy that is on, a rule for each table and a hook.
    *******************************************************************************/
   @Test
   void aCompleteSetupIsOk()
   {
      List<QRecord> providers = List.of(new QRecord().withValue("providerType", "CLOUDFRONT").withValue("enabled", true).withValue("lastHealthStatus", "OK"));
      List<QRecord> hostnames = List.of(new QRecord().withValue("stage", "LIVE"));
      List<QRecord> policies  = List.of(new QRecord().withValue("enabled", true));
      List<QRecord> rules     = List.of(new QRecord().withValue("tableName", "post").withValue("enabled", true));
      List<QRecord> hooks     = List.of(new QRecord().withValue("enabled", true));
      List<QRecord> checks    = List.of(new QRecord());
      List<Item>    items     = CdnSetupStatus.build(providers, hostnames, policies, rules, hooks, checks, List.of("post"));
      assertThat(items).extracting(Item::state).containsOnly(State.OK);
   }



   /*******************************************************************************
    ** The softer states: No CDN only, hostnames not Live, a failing provider, a table without a rule.
    *******************************************************************************/
   @Test
   void softerStatesAreNamed()
   {
      QRecord none = new QRecord().withValue("providerType", "NONE").withValue("enabled", true);
      List<Item> items = CdnSetupStatus.build(List.of(none), List.of(new QRecord().withValue("stage", "OFF")), List.of(new QRecord().withValue("enabled", false)),
         List.of(), List.of(), List.of(), List.of("post", "page"));
      assertThat(item(items, "Providers").state()).isEqualTo(State.DEFAULT);
      assertThat(item(items, "Hostnames").state()).isEqualTo(State.DEFAULT);
      assertThat(item(items, "Cache policies").state()).isEqualTo(State.DEFAULT);
      assertThat(item(items, "Tag rules").detail()).contains("post, page");

      QRecord failing = new QRecord().withValue("providerType", "FASTLY").withValue("enabled", true).withValue("lastHealthStatus", "FAILED");
      assertThat(item(CdnSetupStatus.build(List.of(failing), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), "Providers").state()).isEqualTo(State.TODO);
      assertThat(item(CdnSetupStatus.build(List.of(failing), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), "Hostnames").state()).isEqualTo(State.TODO);
   }
}
