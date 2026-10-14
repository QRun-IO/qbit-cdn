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


import java.util.List;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.CdnTestSupport.FakePathProvider;
import com.kingsrook.qbits.cdn.CdnTestSupport.FakeTagProvider;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeKind;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** Record change -> purge request -> dispatcher -> provider, with the fakes.
 *******************************************************************************/
class PurgePipelineTest
{
   private final CdnPurgeDispatcher dispatcher = new CdnPurgeDispatcher(new RevalidationHookRunner(name -> null, host -> List.of()));



   /*******************************************************************************
    ** Fresh instance and the post rule.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      CdnTestSupport.setUp();
      CdnTestSupport.postRule();
   }



   /*******************************************************************************
    ** Restore the clock.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      CdnRuntime.setClock(null);
   }



   /*******************************************************************************
    ** All purge rows, oldest first.
    *******************************************************************************/
   private static List<QRecord> purges() throws Exception
   {
      return (new QueryAction().execute(new QueryInput(CdnTables.PURGE_REQUEST).withFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy("id")))).getRecords());
   }



   /*******************************************************************************
    ** A published post change on a path provider: flush window, planned paths
    ** (hub skipped, wildcards), IN_PROGRESS, then SUCCEEDED on poll.
    *******************************************************************************/
   @Test
   void publishedChangeIsPlannedSentAndCompleted() throws Exception
   {
      CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
      QRecord post = CdnTestSupport.insert("post", new QRecord().withValue("slug", "my-post").withValue("title", "T").withValue("blogId", 1).withValue("isPublished", true)).get(0);
      List<QRecord> rows = purges();
      assertThat(rows).hasSize(1);
      assertEquals("PENDING", rows.get(0).getValueString("status"));
      assertThat(rows.get(0).getValueString("tags")).contains("post:" + post.getValue("id")).contains("blog:1:list");
      assertThat(rows.get(0).getValueString("paths")).contains("/blog/quick-bits/my-post*").contains("hub:/blog/quick-bits");

      dispatcher.runOnce();
      assertThat(FakePathProvider.SENT).as("inside the flush window").isEmpty();

      CdnRuntime.setClock(java.time.Clock.fixed(CdnTestSupport.NOW.plusSeconds(31), java.time.ZoneOffset.UTC));
      dispatcher.runOnce();
      assertThat(FakePathProvider.SENT).hasSize(1);
      assertEquals(CdnPurgeKind.PATHS, FakePathProvider.SENT.get(0).kind());
      assertThat(FakePathProvider.SENT.get(0).items()).containsExactly("/blog/quick-bits/my-post*", "/v1/posts/" + post.getValue("id") + "*");
      QRecord sent = purges().get(0);
      assertEquals("SUCCEEDED", sent.getValueString("status"), "polled in the same pass");
      assertEquals(2, sent.getValueInteger("billablePaths"));
      assertThat(sent.getValueString("providerRequestId")).startsWith("I-");
   }



   /*******************************************************************************
    ** Draft edits and unwatched field changes purge nothing; a slug change
    ** purges the old and new paths; merged rows go out in one call.
    *******************************************************************************/
   @Test
   void filtersAndMerging() throws Exception
   {
      CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, "{\"flushWindowSeconds\":\"0\"}");
      CdnTestSupport.insert("post", new QRecord().withValue("slug", "draft").withValue("blogId", 1).withValue("isPublished", false));
      assertThat(purges()).isEmpty();

      QRecord post = CdnTestSupport.insert("post", new QRecord().withValue("slug", "a").withValue("title", "A").withValue("blogId", 1).withValue("isPublished", true)).get(0);
      new UpdateAction().execute(new UpdateInput("post").withRecord(new QRecord().withValue("id", post.getValue("id")).withValue("blogId", 1)));
      assertThat(purges()).as("blogId is not watched").hasSize(1);

      new UpdateAction().execute(new UpdateInput("post").withRecord(new QRecord().withValue("id", post.getValue("id")).withValue("slug", "b")));
      assertThat(purges()).hasSize(2);
      dispatcher.runOnce();
      assertThat(FakePathProvider.SENT).hasSize(1);
      assertThat(FakePathProvider.SENT.get(0).items()).contains("/blog/quick-bits/a*", "/blog/quick-bits/b*");
      assertThat(purges()).extracting(row -> row.getValueString("status")).containsExactly("SUCCEEDED", "MERGED");
   }



   /*******************************************************************************
    ** Retryable failures back off and end DEAD; permanent ones go DEAD at once.
    *******************************************************************************/
   @Test
   void failuresRetryThenDie() throws Exception
   {
      CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, "{\"flushWindowSeconds\":\"0\"}");
      CdnTestSupport.insert("post", new QRecord().withValue("slug", "x").withValue("title", "X").withValue("blogId", 1).withValue("isPublished", true));
      FakePathProvider.failure = new CdnException("throttled", true, null);
      java.time.Instant now = CdnTestSupport.NOW;
      for(int attempt = 1; attempt <= 5; attempt++)
      {
         dispatcher.runOnce();
         QRecord row = purges().get(0);
         assertEquals(attempt, row.getValueInteger("attempts"));
         assertEquals(attempt < 5 ? "FAILED" : "DEAD", row.getValueString("status"));
         now = now.plusSeconds(3601);
         CdnRuntime.setClock(java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
      }

      CdnTestSupport.insert("post", new QRecord().withValue("slug", "y").withValue("title", "Y").withValue("blogId", 1).withValue("isPublished", true));
      FakePathProvider.failure = new CdnException("access denied", false, null);
      dispatcher.runOnce();
      assertEquals("DEAD", purges().get(1).getValueString("status"));
      assertThat(purges().get(1).getValueString("errorMessage")).contains("access denied");
   }



   /*******************************************************************************
    ** A tag provider gets tags; the NONE provider and non-live hostnames
    ** record skipped rows.
    *******************************************************************************/
   @Test
   void tagProviderAndSkippedStatuses() throws Exception
   {
      CdnTestSupport.liveProvider(CdnProviderType.FASTLY, "{\"flushWindowSeconds\":\"0\"}");
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "none").withValue("providerType", "NONE").withValue("enabled", true));
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "off").withValue("providerType", "CLOUDFRONT").withValue("enabled", true));
      CdnTestSupport.insert("post", new QRecord().withValue("slug", "t").withValue("title", "T").withValue("blogId", 1).withValue("isPublished", true));
      assertThat(purges()).extracting(row -> row.getValueString("status")).containsExactly("PENDING", "SKIPPED_NONE_PROVIDER", "SKIPPED_STAGE_OFF");
      dispatcher.runOnce();
      assertThat(FakeTagProvider.SENT).hasSize(1);
      assertEquals(CdnPurgeKind.TAG, FakeTagProvider.SENT.get(0).kind());
      assertThat(FakeTagProvider.SENT.get(0).items()).contains("blog:1:list");
      assertEquals("SUCCEEDED", purges().get(0).getValueString("status"));
   }



   /*******************************************************************************
    ** Over the hard limit in DEFER mode, the purge waits for the TTL; a delete
    ** of a published post still purges.
    *******************************************************************************/
   @Test
   void budgetDeferralAndDeletes() throws Exception
   {
      Integer provider = CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT,
         "{\"flushWindowSeconds\":\"0\",\"overBudgetMode\":\"DEFER\",\"softPathLimit\":\"0\",\"hardPathLimit\":\"1\"}");
      CdnTestSupport.insert(CdnTables.PURGE_REQUEST, new QRecord().withValue("cdnProviderId", provider).withValue("status", "SUCCEEDED")
         .withValue("billablePaths", 1).withValue("sentDate", CdnTestSupport.NOW.minusSeconds(60)));
      QRecord post = CdnTestSupport.insert("post", new QRecord().withValue("slug", "d").withValue("title", "D").withValue("blogId", 1).withValue("isPublished", true)).get(0);
      dispatcher.runOnce();
      assertEquals("DEFERRED_BUDGET", purges().get(1).getValueString("status"));
      assertEquals(1, CdnPurgeDispatcher.billedThisMonth(new QRecord().withValue("id", provider), CdnTestSupport.NOW));

      new com.kingsrook.qqq.backend.core.actions.tables.DeleteAction().execute(new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput("post")
         .withPrimaryKeys(List.of(post.getValue("id"))));
      assertThat(purges()).hasSize(3);
   }
}
