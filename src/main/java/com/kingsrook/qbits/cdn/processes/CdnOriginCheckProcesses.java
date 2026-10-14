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


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.checks.CdnOriginCheck;
import com.kingsrook.qbits.cdn.model.CdnHostnameStage;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.permissions.CdnPermissions;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
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
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.cdn.processes.CdnProcesses.line;


/*******************************************************************************
 ** Check Origin Headers: a record action on cdn_hostname (standard streamed ETL:
 ** Review lists the hostnames, Result says how many sample objects had the right
 ** Cache-Control) and a daily scheduled run over every hostname that is not Off.
 ** The work is CdnOriginCheck; each run writes one cdn_origin_check row per hostname.
 *******************************************************************************/
public final class CdnOriginCheckProcesses
{
   public static final String CHECK_ORIGIN     = "cdnCheckOrigin";
   public static final String SCHEDULED_ORIGIN = "cdnScheduledCheckOrigin";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnOriginCheckProcesses()
   {
   }



   /*******************************************************************************
    ** The processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes(CdnQBitConfig config)
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      QProcessMetaData       action    = CdnProcesses.etl(CHECK_ORIGIN, "Check Origin Headers", CdnTables.HOSTNAME, CheckTransform.class, CheckLoad.class,
         "This is a preview of the hostnames whose origin will be asked for a few sample pages, to check their Cache-Control headers.");
      action.setIcon(new QIcon("fact_check"));
      processes.add(CdnProcesses.permission(action, CdnPermissions.ADMIN));

      QProcessMetaData scheduled = new QProcessMetaData().withName(SCHEDULED_ORIGIN).withLabel("Check CDN Origin Headers").withIcon(new QIcon("schedule")).withIsHidden(true)
         .withPermissionRules(CdnTables.permissionRules())
         .withStep(new QBackendStepMetaData().withName("run").withCode(new QCodeReference(ScheduledStep.class)));
      if(config.getSchedulerName() != null)
      {
         scheduled.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withCronExpression("0 45 2 * * ?").withCronTimeZoneId("UTC"));
      }
      processes.add(CdnProcesses.permission(scheduled, CdnPermissions.ADMIN));
      return (processes);
   }



   /*******************************************************************************
    ** Review: the hostnames that will be checked.
    *******************************************************************************/
   public static class CheckTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine hosts = line(Status.OK, "hostname will be checked", "hostnames will be checked", "hostname was checked", "hostnames were checked");



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord hostname : input.getRecords())
         {
            hosts.incrementCountAndAddPrimaryKey(hostname.getValue("id"));
            output.getRecords().add(hostname);
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         hosts.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result: check each hostname and record the row.
    *******************************************************************************/
   public static class CheckLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine clean      = line(Status.OK, "hostname will be checked", "hostnames will be checked", "hostname has the right headers", "hostnames have the right headers");
      private final ProcessSummaryLine wrong      = line(Status.WARNING, "page has a missing or wrong Cache-Control", "pages have a missing or wrong Cache-Control",
         "page has a missing or wrong Cache-Control", "pages have a missing or wrong Cache-Control");
      private final ProcessSummaryLine unreadable = line(Status.WARNING, "page could not be read", "pages could not be read", "page could not be read", "pages could not be read");
      private final List<String>       problems   = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord hostname : input.getRecords())
         {
            CdnOriginCheck.Result result = CdnOriginCheck.checkAndRecord(hostname);
            if(result.wrong() == 0 && result.unreadable() == 0)
            {
               clean.incrementCountAndAddPrimaryKey(hostname.getValue("id"));
            }
            wrong.incrementCount(result.wrong());
            unreadable.incrementCount(result.unreadable());
            result.problems().forEach(problem -> problems.add(result.scope() + problem));
            output.getRecords().add(hostname);
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         clean.addSelfToListIfAnyCount(lines);
         wrong.setBulletsOfText(new ArrayList<>(problems));
         wrong.addSelfToListIfAnyCount(lines);
         unreadable.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Scheduled: every hostname that is not Off.
    *******************************************************************************/
   public static class ScheduledStep implements BackendStep
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord hostname : new QueryAction().execute(new QueryInput(CdnTables.HOSTNAME)
            .withFilter(new QQueryFilter(new QFilterCriteria("stage", QCriteriaOperator.NOT_EQUALS, CdnHostnameStage.OFF.name())))).getRecords())
         {
            CdnOriginCheck.checkAndRecord(hostname);
         }
      }
   }
}
