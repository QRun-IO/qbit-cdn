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


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnHostnameStage;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.CountAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.count.CountInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The Setup card on the CDN home: each configuration table, whether it is set up,
 ** and the next step. Reads the small configuration tables the same way the runtime
 ** does (enabled rows only where the runtime filters on enabled, the application's
 ** purgeable tables from the qbit config).
 *******************************************************************************/
public final class CdnSetupStatus
{
   /*******************************************************************************
    ** OK: in place. TODO: needs doing. DEFAULT: working on defaults. OPTIONAL: off
    ** until set up.
    *******************************************************************************/
   public enum State
   {
      OK, TODO, DEFAULT, OPTIONAL
   }



   /*******************************************************************************
    ** One line of the card.
    *******************************************************************************/
   public record Item(String label, String tableName, State state, String detail, String nextStep)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnSetupStatus()
   {
   }



   /*******************************************************************************
    ** Build the items from the tables.
    *******************************************************************************/
   public static List<Item> load() throws QException
   {
      CdnQBitConfig config    = CdnRuntime.getConfig();
      List<QRecord> providers = all(CdnTables.PROVIDER);
      List<QRecord> hostnames = all(CdnTables.HOSTNAME);
      List<QRecord> policies  = all(CdnTables.CACHE_POLICY);
      List<QRecord> rules     = all(CdnTables.TABLE_TAG_RULE);
      List<QRecord> hooks     = all(CdnTables.REVALIDATION_HOOK);
      long          checks    = new CountAction().execute(new CountInput(CdnTables.ORIGIN_CHECK)).getCount();
      boolean       scheduled = config != null && config.getSchedulerName() != null;
      return (build(providers, hostnames, policies, rules, hooks, checks, config == null ? List.of() : config.getPurgeableTables(), scheduled));
   }



   /*******************************************************************************
    ** The items for given rows (no queries; for tests and reuse).
    *******************************************************************************/
   public static List<Item> build(List<QRecord> providers, List<QRecord> hostnames, List<QRecord> policies, List<QRecord> rules, List<QRecord> hooks,
                                  long checks, List<String> purgeableTables, boolean scheduled)
   {
      List<Item> items = new ArrayList<>();

      //////////////
      // provider //
      //////////////
      List<QRecord> enabledProviders = providers.stream().filter(p -> Boolean.TRUE.equals(p.getValueBoolean("enabled"))).toList();
      List<QRecord> real             = enabledProviders.stream().filter(p -> !CdnProviderType.NONE.name().equals(p.getValueString("providerType"))).toList();
      if(providers.isEmpty())
      {
         items.add(new Item("Providers", CdnTables.PROVIDER, State.TODO, "No provider: changes are not even recorded as purges.",
            "Run Create default provider (No CDN: records purges, sends nothing), or add your CDN with New."));
      }
      else if(enabledProviders.isEmpty())
      {
         items.add(new Item("Providers", CdnTables.PROVIDER, State.TODO, "Every provider is turned off.", "Turn one on (Enabled)."));
      }
      else if(real.isEmpty())
      {
         items.add(new Item("Providers", CdnTables.PROVIDER, State.DEFAULT, "Only No CDN: purges are recorded and nothing is sent.",
            "Add your CDN with New (type, distribution id, and the environment variable that holds its key), then Test Connection."));
      }
      else
      {
         boolean failing = real.stream().anyMatch(p -> "FAILED".equals(p.getValueString("lastHealthStatus")));
         items.add(failing
            ? new Item("Providers", CdnTables.PROVIDER, State.TODO, "A provider's last connection test failed.", "Check its key variable and run Test Connection.")
            : new Item("Providers", CdnTables.PROVIDER, State.OK, real.size() + " CDN provider" + (real.size() == 1 ? "" : "s") + " turned on.", null));
      }

      ///////////////
      // hostnames //
      ///////////////
      if(hostnames.isEmpty())
      {
         items.add(real.isEmpty()
            ? new Item("Hostnames", CdnTables.HOSTNAME, State.OPTIONAL, "None: nothing sits behind a CDN yet.", "Add each site hostname once a CDN provider exists.")
            : new Item("Hostnames", CdnTables.HOSTNAME, State.TODO, "A CDN is set up but no hostname uses it, so nothing is purged.", "Add the hostnames behind it, at stage Off, and move them to Headers only, then Live."));
      }
      else
      {
         long live = hostnames.stream().filter(h -> (CdnHostnameStage.LIVE.name().equals(h.getValueString("stage")) || CdnHostnameStage.LOCKED.name().equals(h.getValueString("stage")))
            && Boolean.TRUE.equals(h.getValueBoolean("purgeEnabled"))).count();
         items.add(live == 0
            ? new Item("Hostnames", CdnTables.HOSTNAME, State.DEFAULT, hostnames.size() + " hostname" + (hostnames.size() == 1 ? "" : "s") + ", none Live: purges are recorded, not sent.",
               "When the CDN serves a hostname, move it to Live (and leave Purge enabled on).")
            : new Item("Hostnames", CdnTables.HOSTNAME, State.OK, live + " of " + hostnames.size() + " hostnames Live.", null));
      }

      ////////////////////////
      // cache policies     //
      ////////////////////////
      long enabledPolicies = policies.stream().filter(p -> Boolean.TRUE.equals(p.getValueBoolean("enabled"))).count();
      items.add(policies.isEmpty()
         ? new Item("Cache policies", CdnTables.CACHE_POLICY, State.OPTIONAL, "None: responses keep the headers your application sets.",
            "Run Create default policies for examples. Policies only change responses when the application calls CdnResponseHeaderPolicy.")
         : enabledPolicies == 0
            ? new Item("Cache policies", CdnTables.CACHE_POLICY, State.DEFAULT, policies.size() + " polic" + (policies.size() == 1 ? "y" : "ies") + ", all turned off.",
               "Edit one and turn it on. It takes effect only where the application calls CdnResponseHeaderPolicy.")
            : new Item("Cache policies", CdnTables.CACHE_POLICY, State.OK, enabledPolicies + " polic" + (enabledPolicies == 1 ? "y" : "ies") + " turned on (applied where the application calls CdnResponseHeaderPolicy).", null));

      ////////////////////////////////////////
      // tag rules: every purgeable table   //
      ////////////////////////////////////////
      Set<String> covered = new HashSet<>();
      rules.stream().filter(r -> Boolean.TRUE.equals(r.getValueBoolean("enabled"))).forEach(r -> covered.add(r.getValueString("tableName")));
      List<String> uncovered = purgeableTables.stream().filter(t -> !covered.contains(t)).toList();
      if(purgeableTables.isEmpty())
      {
         items.add(new Item("Tag rules", CdnTables.TABLE_TAG_RULE, State.OPTIONAL, "The application lists no purgeable tables, so record changes purge nothing.", "List them in the qbit config (purgeableTables)."));
      }
      else if(uncovered.isEmpty())
      {
         items.add(new Item("Tag rules", CdnTables.TABLE_TAG_RULE, State.OK, "Every purgeable table has a rule (" + String.join(", ", purgeableTables) + ").", null));
      }
      else
      {
         items.add(new Item("Tag rules", CdnTables.TABLE_TAG_RULE, State.TODO, "No rule for " + String.join(", ", uncovered) + ": changes to them purge nothing.",
            "Run Create default rules (tags only), then add invalidation paths where your CDN purges by path."));
      }

      ///////////////////////
      // revalidation hooks //
      ///////////////////////
      long enabledHooks = hooks.stream().filter(h -> Boolean.TRUE.equals(h.getValueBoolean("enabled"))).count();
      items.add(enabledHooks == 0
         ? new Item("Revalidation hooks", CdnTables.REVALIDATION_HOOK, State.OPTIONAL, "None.", "Add one if a front end keeps its own cache (for example Next.js) that must refresh before the CDN is purged.")
         : new Item("Revalidation hooks", CdnTables.REVALIDATION_HOOK, State.OK, enabledHooks + " hook" + (enabledHooks == 1 ? "" : "s") + " turned on.", null));

      ////////////////////
      // origin checks  //
      ////////////////////
      items.add(checks == 0
         ? new Item("Origin checks", CdnTables.ORIGIN_CHECK, State.OPTIONAL, "Never run.",
            "Run Check Origin Headers on Hostnames." + (scheduled ? " Hostnames that are not Off are also checked every night." : " No scheduler is configured, so nothing runs it automatically."))
         : new Item("Origin checks", CdnTables.ORIGIN_CHECK, State.OK, checks + " check" + (checks == 1 ? "" : "s") + " recorded.", null));
      return (items);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static List<QRecord> all(String table) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table)).getRecords());
   }
}
