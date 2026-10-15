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

package com.kingsrook.qbits.cdn;


import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundleLayer;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.testing.HelpBundleAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Coverage for shipped help and names bound from the host configuration.
 *******************************************************************************/
class CdnHelpBundleTest
{
   @TempDir
   Path temporary;



   /*******************************************************************************
    ** The resource covers all fixed metadata, without host objects or exclusions.
    *******************************************************************************/
   @Test
   void shippedBundleIsComplete() throws Exception
   {
      QInstance instance = new QInstance();
      instance.addBackend(new QBackendMetaData().withName("memory").withBackendType(MemoryBackendModule.class));
      new CdnQBitProducer().withQBitConfig(new CdnQBitConfig().withBackendName("memory")).produce(instance);
      // Core adds bulk CRUD processes during enrichment; those belong to core.
      Set<String> ownedProcesses = Set.copyOf(instance.getProcesses().keySet());
      Set<String> ownedWidgets = Set.copyOf(instance.getWidgets().keySet());
      Set<String> ownedSources = Set.copyOf(instance.getPossibleValueSources().keySet());
      new QInstanceEnricher(instance).enrich();
      HelpBundleAssertions.forInstance(instance).bundles("help/qbit-cdn.yaml")
         .owning(target -> target.name().startsWith("cdn")
            && (!target.kind().equals("process") || ownedProcesses.contains(target.name()))).assertComplete();
      assertThat(instance.getTable("helpContent")).isNull();
      var bundle = HelpBundles.of(instance).getBundles().get(0);
      assertThat(bundle.getId()).isEqualTo("qbit-cdn");
      assertThat(bundle.getVersion()).as("the help bundle carries the artifact version").isEqualTo(System.getProperty("qbit.artifactVersion"));
      assertThat(bundle.getLayer()).isEqualTo(HelpBundleLayer.QBIT);
      assertThat(bundle.getEntries()).extracting(entry -> entry.getKey()).doesNotHaveDuplicates();
      // Core's gate does not require widgets or possible-value sources yet.
      ownedWidgets.forEach(name -> assertThat(bundle.getEntries()).as(name)
         .anyMatch(entry -> entry.getKey().equals("widget:" + name + ";slot:label")));
      ownedSources.forEach(name -> assertThat(bundle.getEntries()).as(name)
         .anyMatch(entry -> entry.getKey().equals("possibleValueSource:" + name + ";slot:help")));
   }



   /*******************************************************************************
    ** Check the registered bundle, including host-named actions and a renamed app,
    ** through the core gate using a temporary classpath resource.
    *******************************************************************************/
   @Test
   void configuredBundleIsComplete() throws Exception
   {
      QInstance instance = CdnTestSupport.setUp();
      var config = CdnRuntime.getConfig().withAppName("edgeConsole");
      QInstance renamed = new QInstance();
      instance.getBackends().values().forEach(renamed::addBackend);
      renamed.addTable(instance.getTable("post"));
      new CdnQBitProducer().withQBitConfig(config).produce(renamed);
      Set<String> ownedProcesses = Set.copyOf(renamed.getProcesses().keySet());
      new QInstanceEnricher(renamed).enrich();
      var bundle = HelpBundles.of(renamed).getBundles().get(0);
      assertThat(bundle.getEntries()).anyMatch(entry -> entry.getKey().equals("process:cdnPurge_post;slot:help")
         && entry.getContent().contains("Review the selected records"));
      assertThat(bundle.getEntries()).anyMatch(entry -> entry.getKey().equals("app:edgeConsole;slot:help"));
      assertThat(bundle.getEntries()).noneMatch(entry -> entry.getKey().startsWith("app:cdnAdmin;"));
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      mapper.writeValue(temporary.resolve("configured.yaml").toFile(), Map.of("bundle", bundle.getId(),
         "version", bundle.getVersion(), "layer", bundle.getLayer(), "entries", bundle.getEntries()));
      ClassLoader original = Thread.currentThread().getContextClassLoader();
      try(URLClassLoader loader = new URLClassLoader(new java.net.URL[] { temporary.toUri().toURL() }, original))
      {
         Thread.currentThread().setContextClassLoader(loader);
         HelpBundleAssertions.forInstance(renamed).bundles("configured.yaml")
            .owning(target -> (target.name().startsWith("cdn") || target.name().equals("edgeConsole"))
               && (!target.kind().equals("process") || ownedProcesses.contains(target.name()))).assertComplete();
         mapper.writeValue(temporary.resolve("host.yaml").toFile(), Map.of("bundle", "host", "version", "1",
            "layer", "HOST", "entries", java.util.List.of(Map.of("key", "table:cdn_provider;slot:help", "summary", "Your host instructions."))));
         HelpBundles.register(renamed, "host.yaml");
         assertThat(HelpBundles.of(renamed).resolveWinningEntries()
            .get(new HelpBundles.EntryKey("table:cdn_provider;slot:help", "ALL_SCREENS")).entry().getSummary())
            .isEqualTo("Your host instructions.");
      }
      finally
      {
         Thread.currentThread().setContextClassLoader(original);
      }
   }
}
