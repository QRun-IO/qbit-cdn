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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.permissions.CdnPermissions;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import static com.kingsrook.qbits.cdn.processes.CdnProcesses.line;


/*******************************************************************************
 ** The "Create defaults" table actions that give an empty CDN setup something to
 ** start from. Each is a standard streamed ETL (Review lists the rows that will
 ** be added, Result the rows that were), needs no selected records, needs
 ** cdn.admin, and is insert-only: a row (or the whole kind of row) that exists is
 ** never changed or duplicated.
 ** <ul>
 ** <li>Create default provider (cdn_provider): a "No CDN" provider, so changes
 **     are recorded as would-be purges and nothing is sent, until a real CDN is
 **     added;</li>
 ** <li>Create default policies (cdn_cache_policy): two disabled examples
 **     (immutable assets, never cache) to edit and turn on;</li>
 ** <li>Create default rules (cdn_table_tag_rule): one tag rule per purgeable table
 **     that has none (tags only; paths are the application's to add).</li>
 ** </ul>
 ** These are actions, not a database seed, because the qbit ships no DDL and a
 ** host chooses its own column names; they work on whatever the backend is.
 *******************************************************************************/
public final class CdnDefaultsProcesses
{
   public static final String CREATE_PROVIDER = "cdnCreateDefaultProvider";
   public static final String CREATE_POLICIES = "cdnCreateDefaultPolicies";
   public static final String CREATE_RULES    = "cdnCreateDefaultRules";

   public static final String NONE_PROVIDER_NAME = "No CDN (record only)";
   public static final String ASSETS_POLICY_NAME = "Static assets (immutable)";
   public static final String NEVER_POLICY_NAME  = "Never cache (default)";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnDefaultsProcesses()
   {
   }



   /*******************************************************************************
    ** The three actions.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      return (List.of(
         define(CREATE_PROVIDER, "Create default provider", CdnTables.PROVIDER, ProviderExtract.class,
            "This provider will be added. It records purges and sends nothing, until you add a real CDN."),
         define(CREATE_POLICIES, "Create default policies", CdnTables.CACHE_POLICY, PolicyExtract.class,
            "These policies will be added turned off, as examples to edit and turn on."),
         define(CREATE_RULES, "Create default rules", CdnTables.TABLE_TAG_RULE, RuleExtract.class,
            "These tag rules will be added, one for each purgeable table that has none.")));
   }



   /*******************************************************************************
    ** One table action.
    *******************************************************************************/
   private static QProcessMetaData define(String name, String label, String table, Class<? extends AbstractExtractStep> extract, String preview)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, preview + " Existing rows are never changed.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(extract, DefaultsTransform.class, DefaultsLoad.class, values);
      process.setName(name);
      process.setLabel(label);
      process.setIcon(new QIcon("playlist_add"));
      process.setTableName(table);
      process.setMinInputRecords(0);
      process.setPermissionRules(CdnTables.permissionRules());
      return (CdnProcesses.permission(process, CdnPermissions.ADMIN));
   }



   /*******************************************************************************
    ** The rows the provider action would add: the No CDN provider, when the table
    ** has no NONE provider at all.
    *******************************************************************************/
   public static List<Map<String, Serializable>> missingProviders() throws QException
   {
      for(QRecord row : all(CdnTables.PROVIDER))
      {
         if(CdnProviderType.NONE.name().equals(row.getValueString("providerType")))
         {
            return (List.of());
         }
      }
      Map<String, Serializable> row = new LinkedHashMap<>();
      row.put("name", NONE_PROVIDER_NAME);
      row.put("providerType", CdnProviderType.NONE.name());
      row.put("enabled", true);
      return (List.of(row));
   }



   /*******************************************************************************
    ** The policies the action would add, by name: both disabled, so adding them
    ** changes no response until an administrator turns one on.
    *******************************************************************************/
   public static List<Map<String, Serializable>> missingPolicies() throws QException
   {
      Set<String> existing = new HashSet<>();
      all(CdnTables.CACHE_POLICY).forEach(row -> existing.add(row.getValueString("name")));
      List<Map<String, Serializable>> rows = new ArrayList<>();
      if(!existing.contains(ASSETS_POLICY_NAME))
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("name", ASSETS_POLICY_NAME);
         row.put("matchType", "GLOB");
         row.put("matchValue", "/assets/*");
         row.put("policyClass", "IMMUTABLE");
         row.put("browserMaxAge", 31536000);
         row.put("edgeMaxAge", 31536000);
         row.put("immutable", true);
         row.put("noStore", false);
         row.put("priority", 10);
         row.put("enabled", false);
         row.put("seeded", true);
         rows.add(row);
      }
      if(!existing.contains(NEVER_POLICY_NAME))
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("name", NEVER_POLICY_NAME);
         row.put("matchType", "PREFIX");
         row.put("matchValue", "/");
         row.put("policyClass", "NEVER");
         row.put("immutable", false);
         row.put("noStore", true);
         row.put("priority", 1000);
         row.put("enabled", false);
         row.put("seeded", true);
         rows.add(row);
      }
      return (rows);
   }



   /*******************************************************************************
    ** The rules the action would add: tags "table" and "table:id" for each purgeable
    ** table without a rule (any rule, enabled or not).
    *******************************************************************************/
   public static List<Map<String, Serializable>> missingRules() throws QException
   {
      CdnQBitConfig config = CdnRuntime.getConfig();
      Set<String>   have   = new HashSet<>();
      all(CdnTables.TABLE_TAG_RULE).forEach(row -> have.add(row.getValueString("tableName")));
      List<Map<String, Serializable>> rows = new ArrayList<>();
      for(String table : config == null ? List.<String>of() : config.getPurgeableTables())
      {
         if(!have.contains(table))
         {
            Map<String, Serializable> row = new LinkedHashMap<>();
            row.put("tableName", table);
            row.put("tagTemplates", table + ":{id}\n" + table);
            row.put("enabled", true);
            rows.add(row);
         }
      }
      return (rows);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static List<QRecord> all(String table) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table)).getRecords());
   }



   /*******************************************************************************
    ** Extract the rows to add (computed; nothing is read from the selected records).
    *******************************************************************************/
   public abstract static class DefaultsExtract extends AbstractExtractStep
   {
      /*******************************************************************************
       ** The table the rows go in.
       *******************************************************************************/
      protected abstract String table();



      /*******************************************************************************
       ** The rows that are missing.
       *******************************************************************************/
      protected abstract List<Map<String, Serializable>> missing() throws QException;



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(Map<String, Serializable> values : missing())
         {
            QRecord record = new QRecord().withTableName(table());
            record.setValues(new LinkedHashMap<>(values));
            getRecordPipe().addRecord(record);
         }
      }
   }



   /*******************************************************************************
    ** The provider rows.
    *******************************************************************************/
   public static class ProviderExtract extends DefaultsExtract
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected String table()
      {
         return (CdnTables.PROVIDER);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected List<Map<String, Serializable>> missing() throws QException
      {
         return (missingProviders());
      }
   }



   /*******************************************************************************
    ** The cache policy rows.
    *******************************************************************************/
   public static class PolicyExtract extends DefaultsExtract
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected String table()
      {
         return (CdnTables.CACHE_POLICY);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected List<Map<String, Serializable>> missing() throws QException
      {
         return (missingPolicies());
      }
   }



   /*******************************************************************************
    ** The tag rule rows.
    *******************************************************************************/
   public static class RuleExtract extends DefaultsExtract
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected String table()
      {
         return (CdnTables.TABLE_TAG_RULE);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      protected List<Map<String, Serializable>> missing() throws QException
      {
         return (missingRules());
      }
   }



   /*******************************************************************************
    ** Review: the rows that will be added.
    *******************************************************************************/
   public static class DefaultsTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine added = line(Status.OK, "default row will be added", "default rows will be added", "default row was added", "default rows were added");



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            added.incrementCount();
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         added.addSelfToListIfAnyCount(lines);
         if(lines.isEmpty())
         {
            lines.add(new ProcessSummaryLine(Status.INFO, null, "Nothing to add: the defaults are already there."));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result: insert through the normal insert, so the user's own permissions apply.
    *******************************************************************************/
   public static class DefaultsLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine added    = line(Status.OK, "default row will be added", "default rows will be added", "default row was added", "default rows were added");
      private final ProcessSummaryLine failed   = line(Status.ERROR, "default row could not be added", "default rows could not be added", "default row could not be added", "default rows could not be added");
      private final List<String>       problems = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord inserted : new InsertAction().execute(new InsertInput(input.getTableName()).withRecords(input.getRecords())).getRecords())
         {
            if(inserted.getErrors() == null || inserted.getErrors().isEmpty())
            {
               added.incrementCountAndAddPrimaryKey(inserted.getValue("id"));
               output.getRecords().add(inserted);
            }
            else
            {
               failed.incrementCount();
               inserted.getErrors().forEach(error -> problems.add(error.getMessage()));
            }
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         added.addSelfToListIfAnyCount(lines);
         failed.setBulletsOfText(new ArrayList<>(problems));
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
