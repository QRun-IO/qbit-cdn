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

package com.kingsrook.qbits.cdn.app;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.dashboard.RenderWidgetAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** The CDN home and Health card: honest empty states, then real data.
 *******************************************************************************/
class CdnHomeTest
{
   /*******************************************************************************
    ** Render a widget through QQQ.
    *******************************************************************************/
   private static String render(String widget) throws Exception
   {
      RenderWidgetInput input = new RenderWidgetInput();
      input.setWidgetMetaData(QContext.getQInstance().getWidget(widget));
      return (((RawHTML) new RenderWidgetAction().execute(input).getWidgetData()).getHtml());
   }



   /*******************************************************************************
    ** Before any CDN: every card says why it is empty.
    *******************************************************************************/
   @Test
   void emptyStates() throws Exception
   {
      CdnTestSupport.setUp();
      String html = render(CdnWidgets.HOME_WIDGET);
      assertThat(html).contains("No CDN provider configured: purges are recorded only.").contains("No edge statistics yet.").contains("No purges recorded yet.")
         .contains("No path-billed provider connected yet.").contains("Origin header checks are not run by this version; nothing has been checked.").contains("Nothing to compare yet.")
         .contains("minmax(min(100%,22rem),1fr)");
      assertThat(render(CdnWidgets.HEALTH_CARD_WIDGET)).contains("No CDN provider configured: purges are recorded only.").contains("href=\"/cdnAdmin\"");
   }



   /*******************************************************************************
    ** With data: connection, stats, purges, budget, origin and drift.
    *******************************************************************************/
   @Test
   void realData() throws Exception
   {
      CdnTestSupport.setUp();
      Integer provider = CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
      new com.kingsrook.qqq.backend.core.actions.tables.UpdateAction().execute(new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(CdnTables.PROVIDER)
         .withRecord(new QRecord().withValue("id", provider).withValue("dnsTarget", "d1.cloudfront.net").withValue("credentialRotatedDate", LocalDate.parse("2026-07-20"))));
      LocalDate today = LocalDate.ofInstant(CdnTestSupport.NOW, java.time.ZoneOffset.UTC);
      long age = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse("2026-07-20"), today);
      CdnTestSupport.insert(CdnTables.EDGE_STAT_DAILY,
         new QRecord().withValue("cdnProviderId", provider).withValue("statDate", today.minusDays(1)).withValue("requests", 1000L).withValue("bytesDownloaded", 2048L)
            .withValue("cacheHitRate", new BigDecimal("91.5")).withValue("error5xxRate", new BigDecimal("0.10")),
         new QRecord().withValue("cdnProviderId", provider).withValue("statDate", today.minusDays(2)).withValue("requests", 500L).withValue("bytesDownloaded", 1024L));
      CdnTestSupport.insert(CdnTables.PURGE_REQUEST,
         new QRecord().withValue("cdnProviderId", provider).withValue("status", "SUCCEEDED").withValue("billablePaths", 4).withValue("sentDate", CdnTestSupport.NOW)
            .withValue("createDate", CdnTestSupport.NOW).withValue("modifyDate", CdnTestSupport.NOW),
         new QRecord().withValue("cdnProviderId", provider).withValue("status", "DEAD").withValue("createDate", CdnTestSupport.NOW).withValue("modifyDate", CdnTestSupport.NOW));
      CdnTestSupport.insert(CdnTables.ORIGIN_CHECK, new QRecord().withValue("scope", "kof22-website-files").withValue("objectsChecked", 396).withValue("correct", 396)
         .withValue("checkDate", CdnTestSupport.NOW));
      CdnTestSupport.insert(CdnTables.DRIFT_FINDING, new QRecord().withValue("cdnProviderId", provider).withValue("area", "BEHAVIOR").withValue("checkedDate", CdnTestSupport.NOW));

      CdnHomeData data = CdnHomeData.load();
      assertEquals("ATTENTION", data.overallState());
      assertEquals(1, data.dead24h());
      assertEquals(4, data.budgets().get(0).billed());
      assertEquals(age, data.providers().get(0).credentialAgeDays());

      String html = CdnHomeView.render(data, "/cdnAdmin");
      assertThat(html).contains("d1.cloudfront.net").contains(age + " days (rotate every 90) - rotation due").contains("1,500").contains("91.5%")
         .contains("<polyline").contains("396 of 396").contains("BEHAVIOR").contains("1 open").contains("/cdnAdmin/cdn_purge_request/")
         .contains("www.example.com").doesNotContain("recorded only (not sent)", "nothing has been checked");
      assertThat(CdnHomeView.renderHealthCard(data, "/cdnAdmin")).contains("Needs attention");
   }



   /*******************************************************************************
    ** Helpers.
    *******************************************************************************/
   @Test
   void helpers()
   {
      assertEquals("&lt;b&gt;&amp;&quot;&#39;", CdnHomeView.esc("<b>&\"'"));
      assertEquals("", CdnHomeView.esc(null));
      assertEquals("512 B", CdnHomeView.bytes(512));
      assertEquals("2.0 KB", CdnHomeView.bytes(2048));
      assertEquals("", CdnHomeView.sparkline(List.of(1L)));
      assertEquals("50.0%", CdnHomeView.percent(new BigDecimal("0.5")));
      assertEquals("91.5%", CdnHomeView.percent(new BigDecimal("91.5")));
   }



   /*******************************************************************************
    ** NONE and disabled providers do not imply a connection or a sent purge.
    *******************************************************************************/
   @Test
   void recordedOnlyHome() throws Exception
   {
      for(boolean none : List.of(true, false))
      {
         CdnTestSupport.setUp();
         Integer provider = CdnTestSupport.liveProvider(none ? CdnProviderType.NONE : CdnProviderType.CLOUDFRONT, null);
         if(!none)
         {
            new com.kingsrook.qqq.backend.core.actions.tables.UpdateAction().execute(
               new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(CdnTables.PROVIDER)
                  .withRecord(new QRecord().withValue("id", provider).withValue("enabled", false)));
         }
         CdnTestSupport.insert(CdnTables.PURGE_REQUEST, new QRecord().withValue("cdnProviderId", provider).withValue("status", "SKIPPED_NONE_PROVIDER"));
         assertThat(render(CdnWidgets.HOME_WIDGET)).contains("No CDN provider configured: purges are recorded only.")
            .contains("The CloudFront distribution is created in the cutover window.").contains("recorded only (not sent)")
            .contains("Origin header checks are not run by this version; nothing has been checked.");
         assertThat(render(CdnWidgets.HEALTH_CARD_WIDGET)).contains("No CDN provider configured: purges are recorded only.");
      }
   }

}
