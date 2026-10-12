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
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.CdnTestSupport.FakePathProvider;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnOperations;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


/*******************************************************************************
 ** Test Connection, Import Edge Stats and Check Config Drift through
 ** RunProcessAction, plus the scheduled variants.
 *******************************************************************************/
class CdnProviderProcessesTest
{
   private Integer provider;



   /*******************************************************************************
    ** One operable provider and one without operations (NONE).
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      CdnTestSupport.setUp();
      provider = CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "none").withValue("providerType", "NONE").withValue("enabled", true));
      CdnRuntime.setEnvironment(name -> "CDN_EXPECTED_CONFIG".equals(name) ? "{}" : null);
   }



   /*******************************************************************************
    ** Restore the environment.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      CdnRuntime.setEnvironment(null);
   }



   /*******************************************************************************
    ** Review then execute on every provider; returns [review, result] lines.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private static List<List<String>> run(String process) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(process);
      input.addValue("recordsParam", "recordIds");
      input.addValue("recordIds", "1,2");
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      RunProcessOutput review = new RunProcessAction().execute(input);
      RunProcessInput go = new RunProcessInput();
      go.setProcessName(process);
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      return (List.of(
         text((List<ProcessSummaryLineInterface>) review.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY), false),
         text((List<ProcessSummaryLineInterface>) result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY), true)));
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
    ** The provider row.
    *******************************************************************************/
   private QRecord row() throws Exception
   {
      return (new GetAction().executeForRecord(new GetInput(CdnTables.PROVIDER).withPrimaryKey(provider)));
   }



   /*******************************************************************************
    ** A good test records the DNS target and origin; a failed one an error line.
    *******************************************************************************/
   @Test
   void testConnection() throws Exception
   {
      List<List<String>> lines = run(CdnProviderProcesses.TEST_CONNECTION);
      assertThat(lines.get(0)).containsExactly("1 provider will be checked", "1 provider does not support this (no change)");
      assertThat(lines.get(1)).containsExactly("1 connection is OK");
      assertEquals("OK", row().getValueString("lastHealthStatus"));
      assertEquals("d1.cloudfront.net", row().getValueString("dnsTarget"));
      assertEquals("origin.example.com:8443", row().getValueString("originHost"));
      assertNotNull(row().getValue("lastSuccessfulTestDate"));

      FakePathProvider.connectionOk = false;
      assertThat(run(CdnProviderProcesses.TEST_CONNECTION).get(1)).containsExactly("1 connection test failed");
      assertEquals("FAILED", row().getValueString("lastHealthStatus"));
      assertEquals("AccessDenied", row().getValueString("lastHealthMessage"));
   }



   /*******************************************************************************
    ** Stats are imported and re-imports update the same rows.
    *******************************************************************************/
   @Test
   void importEdgeStats() throws Exception
   {
      assertThat(run(CdnProviderProcesses.IMPORT_EDGE_STATS).get(1)).containsExactly("2 days of statistics were imported");
      run(CdnProviderProcesses.IMPORT_EDGE_STATS);
      List<QRecord> stats = new QueryAction().execute(new QueryInput(CdnTables.EDGE_STAT_DAILY)).getRecords();
      assertThat(stats).hasSize(2);
      assertThat(stats).anyMatch(stat -> stat.getValueBigDecimal("cacheHitRate") != null && stat.getValueBigDecimal("cacheHitRate").doubleValue() == 90.0);
      assertThat(stats).anyMatch(stat -> stat.getValue("cacheHitRate") == null);
   }



   /*******************************************************************************
    ** Findings are recorded, kept, and resolved when gone; no expected config
    ** is a failed line.
    *******************************************************************************/
   @Test
   void checkDrift() throws Exception
   {
      assertThat(run(CdnProviderProcesses.CHECK_DRIFT).get(1)).containsExactly("1 provider has no drift");

      FakePathProvider.drift = List.of(new CdnOperations.DriftItem("origin.header:X-Origin-Verify", "present", "missing"));
      assertThat(run(CdnProviderProcesses.CHECK_DRIFT).get(1)).containsExactly("1 drift finding is open");
      run(CdnProviderProcesses.CHECK_DRIFT);
      List<QRecord> findings = new QueryAction().execute(new QueryInput(CdnTables.DRIFT_FINDING)).getRecords();
      assertThat(findings).hasSize(1);
      assertNull(findings.get(0).getValue("resolvedDate"));

      FakePathProvider.drift = List.of();
      run(CdnProviderProcesses.CHECK_DRIFT);
      assertNotNull(new QueryAction().execute(new QueryInput(CdnTables.DRIFT_FINDING)).getRecords().get(0).getValue("resolvedDate"));

      CdnRuntime.setEnvironment(name -> null);
      assertThat(run(CdnProviderProcesses.CHECK_DRIFT).get(1).get(0)).startsWith("1 drift check failed: No expected config");
   }



   /*******************************************************************************
    ** The scheduled steps run for every operable provider.
    *******************************************************************************/
   @Test
   void scheduledSteps() throws Exception
   {
      new CdnProviderProcesses.ScheduledStatsStep().run(null, null);
      new CdnProviderProcesses.ScheduledDriftStep().run(null, null);
      assertThat(new QueryAction().execute(new QueryInput(CdnTables.EDGE_STAT_DAILY)).getRecords()).hasSize(2);
      assertEquals("OK", row().getValueString("lastHealthStatus"));
   }
}
