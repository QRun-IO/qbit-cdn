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


import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.permissions.CdnPermissions;
import com.kingsrook.qbits.cdn.purge.CdnPurgeDispatcher;
import com.kingsrook.qbits.cdn.spi.CdnEdgeStat;
import com.kingsrook.qbits.cdn.spi.CdnOperations;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import static com.kingsrook.qbits.cdn.processes.CdnProcesses.line;


/*******************************************************************************
 ** Provider actions on cdn_provider (standard streamed ETL: Review is the dry
 ** run, Result has linked lines): Test Connection, Import Edge Stats and
 ** Check Config Drift; plus daily scheduled imports and drift checks.
 **
 ** The expected edge config for drift is JSON (the OpenTofu output
 ** expected_config) in the environment variable the provider's settings
 ** name as "expectedConfigEnv" (default CDN_EXPECTED_CONFIG).
 *******************************************************************************/
public final class CdnProviderProcesses
{
   public static final String TEST_CONNECTION      = "cdnTestConnection";
   public static final String IMPORT_EDGE_STATS    = "cdnImportEdgeStats";
   public static final String CHECK_DRIFT          = "cdnCheckDrift";
   public static final String SCHEDULED_STATS      = "cdnScheduledImportEdgeStats";
   public static final String SCHEDULED_DRIFT      = "cdnScheduledCheckDrift";
   public static final int    IMPORT_DAYS          = 3;
   public static final String DEFAULT_EXPECTED_ENV = "CDN_EXPECTED_CONFIG";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnProviderProcesses()
   {
   }



   /*******************************************************************************
    ** The processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes(CdnQBitConfig config)
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      processes.add(CdnProcesses.permission(CdnProcesses.etl(TEST_CONNECTION, "Test Connection", CdnTables.PROVIDER, OperationsTransform.class,
         TestConnectionLoad.class, "This is a preview of the CDN providers whose connection will be tested."), CdnPermissions.ADMIN));
      processes.add(CdnProcesses.permission(CdnProcesses.etl(IMPORT_EDGE_STATS, "Import Edge Stats", CdnTables.PROVIDER, OperationsTransform.class,
         ImportStatsLoad.class, "This is a preview of the CDN providers whose last " + IMPORT_DAYS + " days of edge statistics will be imported."), CdnPermissions.ADMIN));
      processes.add(CdnProcesses.permission(CdnProcesses.etl(CHECK_DRIFT, "Check Config Drift", CdnTables.PROVIDER, OperationsTransform.class,
         CheckDriftLoad.class, "This is a preview of the CDN providers whose edge config will be compared with the expected config."), CdnPermissions.ADMIN));
      processes.add(CdnProcesses.permission(scheduled(config, SCHEDULED_STATS, "Import CDN Edge Stats", ScheduledStatsStep.class, "0 15 2 * * ?"), CdnPermissions.ADMIN));
      processes.add(CdnProcesses.permission(scheduled(config, SCHEDULED_DRIFT, "Check CDN Config Drift", ScheduledDriftStep.class, "0 30 2 * * ?"), CdnPermissions.ADMIN));
      return (processes);
   }



   /*******************************************************************************
    ** A hidden daily process.
    *******************************************************************************/
   private static QProcessMetaData scheduled(CdnQBitConfig config, String name, String label, Class<? extends BackendStep> step, String cron)
   {
      QProcessMetaData process = new QProcessMetaData().withName(name).withLabel(label).withIcon(new QIcon("schedule")).withIsHidden(true)
         .withPermissionRules(CdnTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(step)));
      if(config.getSchedulerName() != null)
      {
         process.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withCronExpression(cron).withCronTimeZoneId("UTC"));
      }
      return (process);
   }



   /*******************************************************************************
    ** The operations of a provider row, or null when unsupported.
    *******************************************************************************/
   static CdnOperations operations(QRecord provider) throws QException
   {
      CdnProviderConfig config = CdnPurgeDispatcher.configOf(provider);
      CdnProviderInterface adapter = CdnRuntime.provider(config.type());
      return (adapter instanceof CdnOperations operations ? operations : null);
   }



   /*******************************************************************************
    ** Review: which providers support the operation.
    *******************************************************************************/
   public static class OperationsTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine supported   = line(Status.OK, "provider will be checked", "providers will be checked",
         "provider was checked", "providers were checked");
      private final ProcessSummaryLine unsupported = line(Status.INFO, "provider does not support this (no change)", "providers do not support this (no change)",
         "provider did not support this (no change)", "providers did not support this (no change)");



      /*******************************************************************************
       ** Split supported and unsupported providers.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : input.getRecords())
         {
            if(operations(provider) != null)
            {
               supported.incrementCountAndAddPrimaryKey(provider.getValue("id"));
               output.getRecords().add(provider);
            }
            else
            {
               unsupported.incrementCountAndAddPrimaryKey(provider.getValue("id"));
            }
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         supported.addSelfToListIfAnyCount(lines);
         unsupported.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Test one provider and record the outcome on its row; returns success.
    *******************************************************************************/
   public static boolean testAndRecord(QRecord provider) throws QException
   {
      CdnOperations operations = operations(provider);
      CdnOperations.ConnectionTest test = operations.connectionTest(CdnPurgeDispatcher.configOf(provider));
      Instant now = CdnRuntime.clock().instant();
      QRecord update = new QRecord().withValue("id", provider.getValue("id")).withValue("lastHealthStatus", test.ok() ? "OK" : "FAILED")
         .withValue("lastHealthMessage", test.message()).withValue("lastHealthCheckDate", now).withValue("modifyDate", now);
      if(test.ok())
      {
         update.setValue("lastSuccessfulTestDate", now);
         if(StringUtils.hasContent(test.dnsTarget()))
         {
            update.setValue("dnsTarget", test.dnsTarget());
         }
         if(StringUtils.hasContent(test.originDomain()))
         {
            update.setValue("originHost", test.originDomain() + (test.originPort() == null ? "" : ":" + test.originPort()));
         }
      }
      new UpdateAction().execute(new UpdateInput(CdnTables.PROVIDER).withRecord(update));
      return (test.ok());
   }



   /*******************************************************************************
    ** Execute: Test Connection.
    *******************************************************************************/
   public static class TestConnectionLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine ok     = line(Status.OK, "connection will be tested", "connections will be tested", "connection is OK", "connections are OK");
      private final ProcessSummaryLine failed = line(Status.ERROR, "connection test will fail", "connection tests will fail", "connection test failed",
         "connection tests failed");



      /*******************************************************************************
       ** Test each provider.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : input.getRecords())
         {
            (testAndRecord(provider) ? ok : failed).incrementCountAndAddPrimaryKey(provider.getValue("id"));
            output.getRecords().add(provider);
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         ok.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Import the last days of stats for one provider (upsert); returns days.
    *******************************************************************************/
   public static int importAndRecord(QRecord provider) throws QException
   {
      LocalDate today = CdnRuntime.clock().instant().atZone(ZoneOffset.UTC).toLocalDate();
      List<CdnEdgeStat> stats;
      try
      {
         stats = operations(provider).importStats(CdnPurgeDispatcher.configOf(provider), today.minusDays(IMPORT_DAYS), today.minusDays(1));
      }
      catch(com.kingsrook.qbits.cdn.spi.CdnException e)
      {
         throw (new QException("Edge stats import failed: " + e.getMessage(), e));
      }
      Instant now = CdnRuntime.clock().instant();
      Serializable providerId = provider.getValue("id");
      List<QRecord> inserts = new ArrayList<>();
      List<QRecord> updates = new ArrayList<>();
      for(CdnEdgeStat stat : stats)
      {
         List<QRecord> existing = new QueryAction().execute(new QueryInput(CdnTables.EDGE_STAT_DAILY).withFilter(new QQueryFilter()
            .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, providerId))
            .withCriteria(new QFilterCriteria("scope", QCriteriaOperator.EQUALS, "DISTRIBUTION"))
            .withCriteria(new QFilterCriteria("statDate", QCriteriaOperator.EQUALS, stat.date())))).getRecords();
         QRecord row = new QRecord().withValue("cdnProviderId", providerId).withValue("scope", "DISTRIBUTION").withValue("statDate", stat.date())
            .withValue("requests", stat.requests()).withValue("bytesDownloaded", stat.bytesDownloaded()).withValue("cacheHitRate", decimal(stat.cacheHitRate()))
            .withValue("error4xxRate", decimal(stat.error4xxRate())).withValue("error5xxRate", decimal(stat.error5xxRate())).withValue("importedDate", now);
         if(existing.isEmpty())
         {
            inserts.add(row);
         }
         else
         {
            updates.add(row.withValue("id", existing.get(0).getValue("id")));
         }
      }
      if(!inserts.isEmpty())
      {
         new InsertAction().execute(new InsertInput(CdnTables.EDGE_STAT_DAILY).withRecords(inserts));
      }
      if(!updates.isEmpty())
      {
         new UpdateAction().execute(new UpdateInput(CdnTables.EDGE_STAT_DAILY).withRecords(updates));
      }
      return (stats.size());
   }



   /*******************************************************************************
    ** Double to BigDecimal (null stays null).
    *******************************************************************************/
   private static java.math.BigDecimal decimal(Double value)
   {
      return (value == null ? null : java.math.BigDecimal.valueOf(value));
   }



   /*******************************************************************************
    ** Execute: Import Edge Stats.
    *******************************************************************************/
   public static class ImportStatsLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine days   = line(Status.OK, "day will be imported", "days will be imported", "day of statistics was imported",
         "days of statistics were imported");
      private final ProcessSummaryLine failed = line(Status.ERROR, "provider will fail", "providers will fail", "provider import failed", "provider imports failed");



      /*******************************************************************************
       ** Import for each provider.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : input.getRecords())
         {
            try
            {
               days.incrementCount(importAndRecord(provider));
            }
            catch(QException e)
            {
               failed.incrementCountAndAddPrimaryKey(provider.getValue("id"));
            }
            output.getRecords().add(provider);
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         days.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Compare one provider and record findings (old open ones are resolved
    ** when they no longer appear); returns the open count.
    *******************************************************************************/
   public static int driftAndRecord(QRecord provider) throws QException
   {
      CdnProviderConfig config = CdnPurgeDispatcher.configOf(provider);
      String envName = config.settings().getOrDefault("expectedConfigEnv", DEFAULT_EXPECTED_ENV);
      String expected = CdnRuntime.environment(envName);
      if(!StringUtils.hasContent(expected))
      {
         throw (new QException("No expected config: set the " + envName + " environment variable to the OpenTofu output expected_config."));
      }
      List<CdnOperations.DriftItem> items;
      try
      {
         items = operations(provider).driftFindings(config, expected);
      }
      catch(com.kingsrook.qbits.cdn.spi.CdnException e)
      {
         throw (new QException("Drift check failed: " + e.getMessage(), e));
      }
      Instant now = CdnRuntime.clock().instant();
      Serializable providerId = provider.getValue("id");
      List<QRecord> open = new QueryAction().execute(new QueryInput(CdnTables.DRIFT_FINDING).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, providerId))
         .withCriteria(new QFilterCriteria("resolvedDate", QCriteriaOperator.IS_BLANK)))).getRecords();
      List<QRecord> updates = new ArrayList<>();
      List<QRecord> inserts = new ArrayList<>();
      for(CdnOperations.DriftItem item : items)
      {
         QRecord match = open.stream().filter(row -> item.area().equals(row.getValueString("area")) && java.util.Objects.equals(item.actual(), row.getValueString("actual")))
            .findFirst().orElse(null);
         if(match != null)
         {
            open.remove(match);
            updates.add(new QRecord().withValue("id", match.getValue("id")).withValue("checkedDate", now));
         }
         else
         {
            inserts.add(new QRecord().withValue("cdnProviderId", providerId).withValue("checkedDate", now).withValue("area", item.area())
               .withValue("expected", item.expected()).withValue("actual", item.actual()).withValue("severity", "WARNING"));
         }
      }
      for(QRecord gone : open)
      {
         updates.add(new QRecord().withValue("id", gone.getValue("id")).withValue("resolvedDate", now).withValue("checkedDate", now));
      }
      if(!inserts.isEmpty())
      {
         new InsertAction().execute(new InsertInput(CdnTables.DRIFT_FINDING).withRecords(inserts));
      }
      if(!updates.isEmpty())
      {
         new UpdateAction().execute(new UpdateInput(CdnTables.DRIFT_FINDING).withRecords(updates));
      }
      return (items.size());
   }



   /*******************************************************************************
    ** Execute: Check Config Drift.
    *******************************************************************************/
   public static class CheckDriftLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine clean    = line(Status.OK, "provider will be compared", "providers will be compared", "provider has no drift",
         "providers have no drift");
      private final ProcessSummaryLine drifting = line(Status.WARNING, "drift finding", "drift findings", "drift finding is open", "drift findings are open");
      private final ProcessSummaryLine failed   = line(Status.ERROR, "check will fail", "checks will fail", "drift check failed", "drift checks failed");



      /*******************************************************************************
       ** Compare each provider.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : input.getRecords())
         {
            try
            {
               int open = driftAndRecord(provider);
               if(open == 0)
               {
                  clean.incrementCountAndAddPrimaryKey(provider.getValue("id"));
               }
               else
               {
                  drifting.incrementCount(open);
               }
            }
            catch(QException e)
            {
               failed.incrementCountAndAddPrimaryKey(provider.getValue("id"));
               failed.setMessageSuffix(": " + e.getMessage());
            }
            output.getRecords().add(provider);
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         clean.addSelfToListIfAnyCount(lines);
         drifting.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Enabled providers that support the operations.
    *******************************************************************************/
   static List<QRecord> operableProviders() throws QException
   {
      List<QRecord> result = new ArrayList<>();
      for(QRecord provider : new QueryAction().execute(new QueryInput(CdnTables.PROVIDER)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords())
      {
         if(operations(provider) != null)
         {
            result.add(provider);
         }
      }
      return (result);
   }



   /*******************************************************************************
    ** Scheduled: daily edge stats for every operable provider.
    *******************************************************************************/
   public static class ScheduledStatsStep implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : operableProviders())
         {
            importAndRecord(provider);
         }
      }
   }



   /*******************************************************************************
    ** Scheduled: daily drift check for every operable
    ** provider.
    *******************************************************************************/
   public static class ScheduledDriftStep implements BackendStep
   {
      /*******************************************************************************
       ** Run.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord provider : operableProviders())
         {
            driftAndRecord(provider);
         }
      }
   }
}
