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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnPurgeSource;
import com.kingsrook.qbits.cdn.model.CdnPurgeStatus;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.permissions.CdnPermissions;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;
import com.kingsrook.qbits.cdn.purge.CdnPurgeDispatcher;
import com.kingsrook.qbits.cdn.purge.CdnPurgeService;
import com.kingsrook.qbits.cdn.purge.TagRuleEngine;
import com.kingsrook.qbits.cdn.spi.CdnPurgeKind;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
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
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The qbit's processes. Every action on records is a standard streamed ETL
 ** process (the Review step is the dry run; the Result step has summary
 ** lines and linked error lines). None is ever in the nav.
 *******************************************************************************/
public final class CdnProcesses
{
   public static final String PURGE_PREFIX      = "cdnPurge_";
   public static final String RETRY_PURGE       = "cdnRetryPurge";
   public static final String PURGE_HOSTNAME    = "cdnPurgeHostname";
   public static final String DISPATCH_PURGES   = "cdnDispatchPurges";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnProcesses()
   {
   }



   /*******************************************************************************
    ** All processes for a config (also records their permissions).
    *******************************************************************************/
   public static List<QProcessMetaData> processes(CdnQBitConfig config)
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      for(String table : config.getPurgeableTables())
      {
         processes.add(permission(etl(PURGE_PREFIX + table, "Purge From CDN", table, PurgeRecordsTransform.class, PurgeRecordsLoad.class,
            "This is a preview of the records whose pages and API reads will be purged from the CDN."), CdnPermissions.PURGE));
      }
      processes.add(permission(etl(RETRY_PURGE, "Retry Purge", CdnTables.PURGE_REQUEST, RetryTransform.class, RetryLoad.class,
         "This is a preview of the purge requests that will be sent again."), CdnPermissions.PURGE));
      processes.add(permission(etl(PURGE_HOSTNAME, "Purge Everything", CdnTables.HOSTNAME, PurgeHostnameTransform.class, PurgeHostnameLoad.class,
         "This is a preview of the hostnames whose CDN distribution will be purged completely."), CdnPermissions.PURGE_ALL));

      QProcessMetaData dispatch = new QProcessMetaData()
         .withName(DISPATCH_PURGES)
         .withLabel("Send CDN Purges")
         .withIcon(new QIcon("send"))
         .withIsHidden(true)
         .withPermissionRules(CdnTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("dispatch").withCode(new QCodeReference(DispatchStep.class)));
      if(config.getSchedulerName() != null)
      {
         dispatch.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName())
            .withRepeatSeconds(config.getDispatchIntervalSeconds()).withInitialDelaySeconds(30));
      }
      processes.add(permission(dispatch, CdnPermissions.ADMIN));
      return (processes);
   }



   /*******************************************************************************
    ** Record the permission a process needs.
    *******************************************************************************/
   static QProcessMetaData permission(QProcessMetaData process, String permission)
   {
      CdnRuntime.processPermissions().put(process.getName(), permission);
      return (process);
   }



   /*******************************************************************************
    ** A standard streamed ETL process with full validation (Review = dry run).
    *******************************************************************************/
   static QProcessMetaData etl(String name, String label, String table, Class<? extends AbstractTransformStep> transform,
      Class<? extends AbstractLoadStep> load, String preview)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, preview);
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, transform, load, values);
      process.setName(name);
      process.setLabel(label);
      process.setTableName(table);
      process.setPermissionRules(CdnTables.permissionRules());
      return (process);
   }



   /*******************************************************************************
    ** A summary line with future (Review) and past (Result) wording.
    *******************************************************************************/
   public static ProcessSummaryLine line(Status status, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }



   /*******************************************************************************
    ** Scheduled: send due purges and poll in-progress ones.
    *******************************************************************************/
   public static class DispatchStep implements BackendStep
   {
      /*******************************************************************************
       ** Run one dispatcher pass.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         new CdnPurgeDispatcher().runOnce();
      }
   }



   /*******************************************************************************
    ** Purge From CDN, review: what the selected records will purge.
    *******************************************************************************/
   public static class PurgeRecordsTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine records  = line(Status.OK, "record will be purged", "records will be purged",
         "record was purged", "records were purged");
      private final ProcessSummaryLine noRule   = line(Status.INFO, "record has no CDN tag rule (no change)", "records have no CDN tag rule (no change)",
         "record had no CDN tag rule (no change)", "records had no CDN tag rule (no change)");
      private final Set<String>        paths    = new LinkedHashSet<>();



      /*******************************************************************************
       ** Evaluate the table's rules for each record (published filters ignored:
       ** a manual purge always purges).
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = input.getValueString(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE);
         List<QRecord> rules = rules(table);
         TagRuleEngine engine = new TagRuleEngine();
         for(QRecord record : input.getRecords())
         {
            List<String> tags = new ArrayList<>();
            List<InvalidationPath> recordPaths = new ArrayList<>();
            for(QRecord rule : rules)
            {
               QRecord unfiltered = new QRecord(rule).withValue("publishedField", null).withValue("onlyWhenFieldsChange", null);
               TagRuleEngine.Result result = engine.evaluate(unfiltered, List.of(record), List.of(record));
               tags.addAll(result.tags());
               recordPaths.addAll(result.paths());
            }
            if(tags.isEmpty() && recordPaths.isEmpty())
            {
               noRule.incrementCountAndAddPrimaryKey(record.getValue("id"));
               continue;
            }
            recordPaths.forEach(path -> paths.add(path.path()));
            record.setValue("cdnTags", String.join("\n", tags));
            record.setValue("cdnPaths", com.kingsrook.qbits.cdn.purge.PurgeText.paths(recordPaths));
            records.incrementCountAndAddPrimaryKey(record.getValue("id"));
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         records.addSelfToListIfAnyCount(lines);
         if(!paths.isEmpty())
         {
            ProcessSummaryLine pathLine = line(Status.INFO, "path is planned (wildcards may merge them)", "paths are planned (wildcards may merge them)",
               "path was planned", "paths were planned");
            pathLine.setCount(paths.size());
            lines.add(pathLine);
         }
         noRule.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Purge From CDN, execute: one purge request (sent at once, hubs included).
    *******************************************************************************/
   public static class PurgeRecordsLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine requested = line(Status.OK, "purge request will be recorded", "purge requests will be recorded",
         "purge request was recorded (sent within seconds)", "purge requests were recorded (sent within seconds)");



      /*******************************************************************************
       ** Record one request per record.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = input.getValueString(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE);
         for(QRecord record : input.getRecords())
         {
            List<QRecord> rows = CdnPurgeService.enqueue(new CdnPurgeService.Request(CdnPurgeKind.TAG.name(),
               com.kingsrook.qbits.cdn.purge.PurgeText.tags(record.getValueString("cdnTags")),
               com.kingsrook.qbits.cdn.purge.PurgeText.paths(record.getValueString("cdnPaths")), true, CdnPurgeSource.RECORD_ACTION, table, record.getValue("id")));
            requested.incrementCount(rows.size());
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         requested.addSelfToListIfAnyCount(lines);
         if(getTransformStep() instanceof PurgeRecordsTransform transform)
         {
            transform.noRule.addSelfToListIfAnyCount(lines);
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Retry Purge, review.
    *******************************************************************************/
   public static class RetryTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine retry = line(Status.OK, "purge request will be sent again", "purge requests will be sent again",
         "purge request was queued again", "purge requests were queued again");
      private final ProcessSummaryLine skip  = line(Status.INFO, "purge request has not failed (no change)", "purge requests have not failed (no change)",
         "purge request had not failed (no change)", "purge requests had not failed (no change)");



      /*******************************************************************************
       ** Only FAILED, DEAD and DEFERRED_BUDGET rows are retried.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            String status = record.getValueString("status");
            if(CdnPurgeStatus.FAILED.name().equals(status) || CdnPurgeStatus.DEAD.name().equals(status) || CdnPurgeStatus.DEFERRED_BUDGET.name().equals(status))
            {
               retry.incrementCountAndAddPrimaryKey(record.getValue("id"));
               output.getRecords().add(record);
            }
            else
            {
               skip.incrementCountAndAddPrimaryKey(record.getValue("id"));
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
         retry.addSelfToListIfAnyCount(lines);
         skip.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Retry Purge, execute: back to PENDING (sent on the next dispatcher pass).
    *******************************************************************************/
   public static class RetryLoad extends AbstractLoadStep
   {
      /*******************************************************************************
       ** Reset the rows.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         List<QRecord> updates = new ArrayList<>();
         for(QRecord record : input.getRecords())
         {
            updates.add(new QRecord().withValue("id", record.getValue("id")).withValue("status", CdnPurgeStatus.PENDING.name())
               .withValue("source", CdnPurgeSource.RETRY.name()).withValue("attempts", 0).withValue("nextAttemptDate", null)
               .withValue("errorMessage", null).withValue("completedDate", null).withValue("modifyDate", CdnRuntime.clock().instant()));
         }
         if(!updates.isEmpty())
         {
            output.getRecords().addAll(new UpdateAction().execute(new UpdateInput(CdnTables.PURGE_REQUEST).withRecords(updates)).getRecords());
         }
      }
   }



   /*******************************************************************************
    ** Purge Everything, review: one whole-distribution purge per provider.
    *******************************************************************************/
   public static class PurgeHostnameTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine providers = line(Status.WARNING,
         "CDN distribution will be purged completely (every hostname on it)", "CDN distributions will be purged completely (every hostname on them)",
         "CDN distribution purge was recorded", "CDN distribution purges were recorded");
      private final Set<Serializable>  seen      = new LinkedHashSet<>();



      /*******************************************************************************
       ** One output record per distinct provider.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            Serializable providerId = record.getValue("cdnProviderId");
            if(providerId != null && seen.add(providerId))
            {
               providers.incrementCountAndAddPrimaryKey(record.getValue("id"));
               output.getRecords().add(record);
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
         providers.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Purge Everything, execute.
    *******************************************************************************/
   public static class PurgeHostnameLoad extends AbstractLoadStep
   {
      /*******************************************************************************
       ** Record an ALL purge for each provider.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            CdnPurgeService.enqueueFor(record.getValue("cdnProviderId"), new CdnPurgeService.Request(CdnPurgeKind.ALL.name(), List.of(), List.of(), true,
               CdnPurgeSource.MANUAL, CdnTables.HOSTNAME, record.getValue("id")));
            output.getRecords().add(record);
         }
      }
   }



   /*******************************************************************************
    ** Enabled rules for a table.
    *******************************************************************************/
   static List<QRecord> rules(String table) throws QException
   {
      return (new QueryAction().execute(new QueryInput(CdnTables.TABLE_TAG_RULE).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("tableName", QCriteriaOperator.EQUALS, table))
         .withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords());
   }
}
