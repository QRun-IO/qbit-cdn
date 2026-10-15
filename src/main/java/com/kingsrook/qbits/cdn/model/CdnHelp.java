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

package com.kingsrook.qbits.cdn.model;


import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.processes.CdnProcesses;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundle;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundleEntry;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;


/*******************************************************************************
 ** Registers CDN help and binds its reusable record-action text to host tables.
 *******************************************************************************/
public final class CdnHelp
{
   public static final String RESOURCE = "help/qbit-cdn.yaml";
   public static final String APP_KEY  = "app:cdnAdmin;slot:help";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnHelp()
   {
   }



   /*******************************************************************************
    ** Registration is metadata-only. Core's startup sync guards the absence of
    ** helpContent, so hosts without the help tables can still install this qbit.
    ** RC.7 supports suffix wildcards only; bind the dynamic purge names explicitly.
    *******************************************************************************/
   public static void register(QInstance qInstance, CdnQBitConfig config)
   {
      HelpBundles.register(qInstance, RESOURCE);
      var bundles = HelpBundles.of(qInstance).getBundles();
      HelpBundle bundle = bundles.get(bundles.size() - 1);
      HelpBundleEntry purgeHelp = bundle.getEntries().stream()
         .filter(entry -> entry.getKey().equals("app:cdnAdmin;slot:purgeRecords")).findFirst().orElseThrow();
      for(String table : config.getPurgeableTables())
      {
         bundle.getEntries().add(new HelpBundleEntry()
            .withKey("process:" + CdnProcesses.PURGE_PREFIX + table + ";slot:help")
            .withSummary(purgeHelp.getSummary()).withContent(purgeHelp.getContent()).withFormat(purgeHelp.getFormat()));
      }
      for(HelpBundleEntry entry : bundle.getEntries())
      {
         if(entry.getKey().startsWith("app:cdnAdmin;"))
         {
            entry.setKey(entry.getKey().replace("app:cdnAdmin;", "app:" + config.getAppName() + ";"));
         }
      }
   }
}
