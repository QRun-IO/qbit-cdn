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


import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnHelp;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.processes.CdnDefaultsProcesses;
import com.kingsrook.qbits.cdn.processes.CdnOriginCheckProcesses;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated and
 ** the qbit should be fully using them"). For every cdn_* table:
 ** - runtime code (not just its model, metadata or home screen) reads or writes it;
 ** - it is listed here with why it starts empty and, where there is one, the table
 **   action that sets it up (this qbit ships no DDL and a host chooses its column
 **   names, so defaults are actions, not a database seed);
 ** - it has bundle help that says what it is for and how its rows get created
 **   (shown on the table screen and when it is empty).
 ** A new table fails this test until it is wired and explained.
 *******************************************************************************/
class CdnTablesGuardrailTest
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "cdn");

   /*******************************************************************************
    ** Why each table starts empty, and where its rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.ofEntries(
      Map.entry(CdnTables.PROVIDER, "host connection config: the Create default provider action adds No CDN; a real CDN needs the host's account and key variable"),
      Map.entry(CdnTables.HOSTNAME, "host-specific: the hostnames the host serves"),
      Map.entry(CdnTables.CACHE_POLICY, "the Create default policies action adds two turned-off examples; turning one on is the host's decision"),
      Map.entry(CdnTables.TABLE_TAG_RULE, "the Create default rules action adds a tags-only rule per purgeable table; paths are the host's"),
      Map.entry(CdnTables.PURGE_REQUEST, "runtime: a record change or a purge action"),
      Map.entry(CdnTables.REVALIDATION_HOOK, "host-specific and optional: a front end with its own cache"),
      Map.entry(CdnTables.EDGE_STAT_DAILY, "runtime: Import Edge Stats and the daily import"),
      Map.entry(CdnTables.ORIGIN_CHECK, "runtime: Check Origin Headers and the nightly check"),
      Map.entry(CdnTables.DRIFT_FINDING, "runtime: Check Config Drift and the nightly check"));

   /*******************************************************************************
    ** The table action that sets a table up, by table.
    *******************************************************************************/
   static final Map<String, String> SETUP_ACTIONS = Map.of(
      CdnTables.PROVIDER, CdnDefaultsProcesses.CREATE_PROVIDER,
      CdnTables.CACHE_POLICY, CdnDefaultsProcesses.CREATE_POLICIES,
      CdnTables.TABLE_TAG_RULE, CdnDefaultsProcesses.CREATE_RULES);

   private QInstance qInstance;



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      qInstance = CdnTestSupport.setUp();
   }



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      List<String> runtimeSource = new ArrayList<>();
      try(Stream<Path> files = Files.walk(MAIN))
      {
         for(Path file : files.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.startsWith(MAIN.resolve("model")) && !p.startsWith(MAIN.resolve("app"))).toList())
         {
            runtimeSource.add(Files.readString(file, StandardCharsets.UTF_8));
         }
      }
      for(String table : cdnTables())
      {
         String  constant = constantFor(table);
         Pattern use      = Pattern.compile("\\bCdnTables\\." + constant + "\\b");
         assertThat(runtimeSource.stream().anyMatch(source -> use.matcher(source).find()))
            .as(table + " (CdnTables." + constant + ") must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is explained, and the setup actions it names exist.
    *******************************************************************************/
   @Test
   void everyTableIsExplained()
   {
      assertThat(cdnTables()).containsExactlyInAnyOrderElementsOf(STARTS_EMPTY.keySet());
      assertThat(CdnTables.ALL).containsExactlyInAnyOrderElementsOf(STARTS_EMPTY.keySet());
      SETUP_ACTIONS.forEach((table, action) ->
      {
         assertThat(qInstance.getProcess(action)).as(action).isNotNull();
         assertThat(qInstance.getProcess(action).getTableName()).isEqualTo(table);
         assertThat(qInstance.getProcess(action).getMinInputRecords()).as(action + " needs no selected records").isEqualTo(0);
      });
      assertThat(qInstance.getProcess(CdnOriginCheckProcesses.CHECK_ORIGIN)).isNotNull();
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created, and
    ** the help of a table with a setup action names it.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      var entries = HelpBundles.of(qInstance).getBundles().get(0).getEntries();
      for(String table : cdnTables())
      {
         var entry = entries.stream().filter(e -> e.getKey().equals("table:" + table + ";slot:help")).findFirst().orElseThrow();
         assertThat(entry.getContent()).as(table + " help says how rows get created").contains("How rows get created");
         if(SETUP_ACTIONS.containsKey(table))
         {
            assertThat(entry.getContent()).as(table + " help names its setup action").contains(qInstance.getProcess(SETUP_ACTIONS.get(table)).getLabel());
         }
      }
      assertThat(entries).anyMatch(e -> e.getKey().equals(CdnHelp.APP_KEY));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<String> cdnTables()
   {
      List<String> tables = qInstance.getTables().values().stream().map(QTableMetaData::getName).filter(n -> n.startsWith("cdn_")).sorted().toList();
      assertThat(tables).hasSize(9);
      return (tables);
   }



   /*******************************************************************************
    ** The CdnTables constant whose value is the table name.
    *******************************************************************************/
   private static String constantFor(String table) throws Exception
   {
      for(Field field : CdnTables.class.getFields())
      {
         if(field.getType() == String.class && table.equals(field.get(null)) && !field.getName().startsWith("PVS_"))
         {
            return (field.getName());
         }
      }
      throw (new IllegalStateException("no constant for " + table));
   }
}
