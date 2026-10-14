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

package com.kingsrook.qbits.cdn.processes;


import java.util.List;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;


/*******************************************************************************
 ** The ETL actions through RunProcessAction: Review (dry run) then Result.
 *******************************************************************************/
class CdnProcessesTest
{
   /*******************************************************************************
    ** Instance, rule and a live path provider.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      CdnTestSupport.setUp();
      CdnTestSupport.postRule();
      CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
   }



   /*******************************************************************************
    ** Run review, then execute; returns [review lines, result lines].
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private static List<List<String>> run(String process, String ids) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(process);
      input.addValue("recordsParam", "recordIds");
      input.addValue("recordIds", ids);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      RunProcessOutput review = new RunProcessAction().execute(input);
      List<ProcessSummaryLineInterface> reviewLines = (List<ProcessSummaryLineInterface>) review.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);

      for(ProcessSummaryLineInterface reviewLine : reviewLines)
      {
         ProcessSummaryLine summary = (ProcessSummaryLine) reviewLine;
         summary.pickMessage(false);
         if(summary.getMessage().contains("purges are recorded only"))
         {
            assertEquals(com.kingsrook.qqq.backend.core.model.actions.processes.Status.WARNING, summary.getStatus());
         }
      }

      RunProcessInput go = new RunProcessInput();
      go.setProcessName(process);
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      List<ProcessSummaryLineInterface> resultLines = (List<ProcessSummaryLineInterface>) result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      return (List.of(text(reviewLines, false), text(resultLines, true)));
   }



   /*******************************************************************************
    ** Lines as shown.
    *******************************************************************************/
   private static List<String> text(List<ProcessSummaryLineInterface> lines, boolean past)
   {
      return (lines == null ? List.of() : lines.stream().map(line ->
      {
         ProcessSummaryLine summary = (ProcessSummaryLine) line;
         summary.pickMessage(past);
         return (summary.getCount() + " " + summary.getMessage());
      }).toList());
   }



   /*******************************************************************************
    ** Purge rows.
    *******************************************************************************/
   private static List<QRecord> purges() throws Exception
   {
      return (new QueryAction().execute(new QueryInput(CdnTables.PURGE_REQUEST).withFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy("id")))).getRecords());
   }



   /*******************************************************************************
    ** Purge From CDN on selected posts (drafts too: a manual purge always
    ** purges), with hubs included.
    *******************************************************************************/
   @Test
   void purgeFromCdn() throws Exception
   {
      QRecord post = CdnTestSupport.insert("post", new QRecord().withValue("slug", "p").withValue("title", "P").withValue("blogId", 1).withValue("isPublished", false)).get(0);
      List<List<String>> lines = run(CdnProcesses.PURGE_PREFIX + "post", String.valueOf(post.getValue("id")));
      assertThat(lines.get(0)).containsExactly("1 record will be purged", "3 paths are planned (wildcards may merge them)");
      assertThat(lines.get(1)).containsExactly("1 purge request was recorded (sent within seconds)");
      QRecord row = purges().get(0);
      assertEquals("RECORD_ACTION", row.getValueString("source"));
      assertEquals(Boolean.TRUE, row.getValueBoolean("includeHubs"));
      assertEquals("PENDING", row.getValueString("status"));
   }



   /*******************************************************************************
    ** Retry Purge only resets failed rows.
    *******************************************************************************/
   @Test
   void retryPurge() throws Exception
   {
      List<QRecord> rows = CdnTestSupport.insert(CdnTables.PURGE_REQUEST,
         new QRecord().withValue("cdnProviderId", 1).withValue("status", "DEAD").withValue("attempts", 5).withValue("errorMessage", "x"),
         new QRecord().withValue("cdnProviderId", 1).withValue("status", "SUCCEEDED"));
      List<List<String>> lines = run(CdnProcesses.RETRY_PURGE, rows.get(0).getValue("id") + "," + rows.get(1).getValue("id"));
      assertThat(lines.get(0)).containsExactly("1 purge request will be sent again", "1 purge request has not failed (no change)");
      assertThat(lines.get(1)).contains("1 purge request was queued again");
      QRecord retried = purges().get(0);
      assertEquals("PENDING", retried.getValueString("status"));
      assertEquals(0, retried.getValueInteger("attempts"));
      assertEquals("RETRY", retried.getValueString("source"));
      assertEquals("SUCCEEDED", purges().get(1).getValueString("status"));
   }



   /*******************************************************************************
    ** Purge Everything records one ALL purge for the hostname's provider only.
    *******************************************************************************/
   @Test
   void purgeEverything() throws Exception
   {
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "other").withValue("providerType", "CLOUDFRONT").withValue("enabled", true));
      List<List<String>> lines = run(CdnProcesses.PURGE_HOSTNAME, "1");
      assertThat(lines.get(0)).containsExactly("1 CDN distribution will be purged completely (every hostname on it)");
      assertThat(lines.get(1)).containsExactly("1 CDN distribution purge was recorded");
      List<QRecord> rows = purges();
      assertThat(rows).hasSize(1);
      assertEquals("ALL", rows.get(0).getValueString("kind"));
      assertEquals(1, rows.get(0).getValueInteger("cdnProviderId"));
   }



   /*******************************************************************************
    ** Every action reports NONE and OFF honestly, including plural results.
    *******************************************************************************/
   @Test
   void recordedOnlySummaries() throws Exception
   {
      for(boolean none : List.of(true, false))
      {
         for(String process : List.of(CdnProcesses.PURGE_PREFIX + "post", CdnProcesses.PURGE_HOSTNAME, CdnProcesses.RETRY_PURGE))
         {
            setUp();
            String table = none ? CdnTables.PROVIDER : CdnTables.HOSTNAME;
            new com.kingsrook.qqq.backend.core.actions.tables.UpdateAction().execute(
               new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(table)
                  .withRecord(new QRecord().withValue("id", 1).withValue(none ? "providerType" : "stage", none ? "NONE" : "OFF")));
            String ids = "1";
            if(process.equals(CdnProcesses.PURGE_PREFIX + "post"))
            {
               CdnTestSupport.insert("post", new QRecord().withValue("slug", "one"), new QRecord().withValue("slug", "two"));
               ids = "1,2";
            }
            else if(process.equals(CdnProcesses.RETRY_PURGE))
            {
               CdnTestSupport.insert(CdnTables.PURGE_REQUEST,
                  new QRecord().withValue("cdnProviderId", 1).withValue("status", "FAILED"),
                  new QRecord().withValue("cdnProviderId", 1).withValue("status", "DEAD"));
               ids = "1,2";
            }
            List<List<String>> lines = run(process, ids);
            String warning = none ? "No CDN provider configured" : "CDN hostnames are not live";
            assertThat(lines.get(0)).anyMatch(line -> line.contains(warning + ": purges are recorded only, not sent."));
            assertThat(lines.get(0)).noneMatch(line -> line.contains("will be purged") || line.contains("will be sent again"));
            String reason = none ? "no CDN provider" : "CDN hostnames are not live";
            assertThat(lines.get(1)).containsExactly(process.equals(CdnProcesses.PURGE_HOSTNAME)
               ? "1 purge request was recorded (not sent: " + reason + ")"
               : "2 purge requests were recorded (not sent: " + reason + ")");
            assertThat(purges()).allMatch(row -> (none ? "SKIPPED_NONE_PROVIDER" : "SKIPPED_STAGE_OFF").equals(row.getValueString("status")));
         }
      }
   }



   /*******************************************************************************
    ** Eligibility considers enabled providers and their purge-enabled hosts.
    *******************************************************************************/
   @Test
   void sendingEligibility() throws Exception
   {
      CdnTestSupport.setUp();
      assertEquals(com.kingsrook.qbits.cdn.model.CdnPurgeStatus.SKIPPED_NONE_PROVIDER,
         com.kingsrook.qbits.cdn.purge.CdnPurgeService.sendingStatus(null));
      CdnTestSupport.liveProvider(CdnProviderType.NONE, null);
      Integer live = CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
      assertEquals(com.kingsrook.qbits.cdn.model.CdnPurgeStatus.PENDING,
         com.kingsrook.qbits.cdn.purge.CdnPurgeService.sendingStatus(null));
      new com.kingsrook.qqq.backend.core.actions.tables.UpdateAction().execute(
         new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(CdnTables.HOSTNAME)
            .withRecord(new QRecord().withValue("id", 2).withValue("purgeEnabled", false)));
      assertEquals(com.kingsrook.qbits.cdn.model.CdnPurgeStatus.SKIPPED_STAGE_OFF,
         com.kingsrook.qbits.cdn.purge.CdnPurgeService.sendingStatus(live));
      new com.kingsrook.qqq.backend.core.actions.tables.UpdateAction().execute(
         new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(CdnTables.PROVIDER)
            .withRecord(new QRecord().withValue("id", live).withValue("enabled", false)));
      assertEquals(com.kingsrook.qbits.cdn.model.CdnPurgeStatus.SKIPPED_NONE_PROVIDER,
         com.kingsrook.qbits.cdn.purge.CdnPurgeService.sendingStatus(null));
   }




   /*******************************************************************************
    ** Without an enabled provider, do not claim a request row was recorded.
    *******************************************************************************/
   @Test
   void absentProvider() throws Exception
   {
      CdnTestSupport.setUp();
      CdnTestSupport.postRule();
      CdnTestSupport.insert("post", new QRecord().withValue("slug", "one"));
      List<List<String>> lines = run(CdnProcesses.PURGE_PREFIX + "post", "1");
      assertThat(lines.get(0)).contains("1 No CDN provider configured: purges are recorded only, not sent.");
      assertThat(lines.get(1)).containsExactly("1 record had no enabled CDN provider (no purge request recorded)");
      assertThat(purges()).isEmpty();
   }

}
