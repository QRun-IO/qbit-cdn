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


import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import com.kingsrook.qbits.cdn.app.CdnWidgets;
import com.kingsrook.qbits.cdn.health.CdnProviderHealthChecks;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.processes.CdnProcesses;
import com.kingsrook.qbits.cdn.purge.CdnRecordChangeListener;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplierMetaData;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Produces the CDN qbit: the cdn_ tables and possible values, the
 ** processes (table/record actions and the scheduled dispatcher), the
 ** widgets, the Admin -> CDN app, and the record-change listener for the
 ** configured purgeable tables.
 *******************************************************************************/
public class CdnQBitProducer implements QBitMetaDataProducer<CdnQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-cdn";
   public static final String VERSION     = loadVersion();

   private CdnQBitConfig qBitConfig;
   private String        namespace;



   /*******************************************************************************
    ** Read the artifact version from the Maven-filtered resource.
    *******************************************************************************/
   private static String loadVersion()
   {
      try(InputStream inputStream = CdnQBitProducer.class.getClassLoader().getResourceAsStream("qbit-cdn.properties"))
      {
         if(inputStream == null)
         {
            throw (new IllegalStateException("Missing qbit-cdn.properties; cannot determine the CDN qbit artifact version."));
         }
         Properties properties = new Properties();
         properties.load(inputStream);
         String version = properties.getProperty("version");
         if(version == null || version.isBlank() || version.contains("${"))
         {
            throw (new IllegalStateException("Missing or unfiltered version in qbit-cdn.properties; build with Maven resource filtering."));
         }
         return (version);
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Could not read the CDN qbit artifact version from qbit-cdn.properties.", e));
      }
   }



   /*******************************************************************************
    ** Fluent setter for the config.
    *******************************************************************************/
   public CdnQBitProducer withQBitConfig(CdnQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the config.
    *******************************************************************************/
   @Override
   public CdnQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Fluent setter for the namespace.
    *******************************************************************************/
   public CdnQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }



   /*******************************************************************************
    ** The qbit's identity.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData().withGroupId(GROUP_ID).withArtifactId(ARTIFACT_ID).withVersion(VERSION).withNamespace(namespace).withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** Producers found by package scanning have no config: skip them.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Build everything and add it to the instance.
    *******************************************************************************/
   @Override
   public MetaDataProducerMultiOutput produce(QInstance qInstance) throws QException
   {
      List<String> errors = new ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      if(!errors.isEmpty())
      {
         throw (new QException("CDN qbit configuration is invalid: " + String.join("; ", errors)));
      }
      QBitMetaData qBit = getQBitMetaData();
      qInstance.addQBit(qBit);
      CdnRuntime.install(qBitConfig);

      MetaDataProducerMultiOutput output = new MetaDataProducerMultiOutput();
      for(QPossibleValueSource possibleValueSource : CdnTables.possibleValueSources())
      {
         output.add(possibleValueSource);
      }
      List<QTableMetaData> tables = new ArrayList<>();
      for(QTableMetaData table : CdnTables.tables(qBitConfig))
      {
         QTableMetaData customized = qBitConfig.getTableMetaDataCustomizer() == null ? table : qBitConfig.getTableMetaDataCustomizer().customizeMetaData(qInstance, table);
         customized.setSourceQBitName(qBit.getName());
         tables.add(customized);
         output.add(customized);
      }
      List<QProcessMetaData> processes = new ArrayList<>(CdnProcesses.processes(qBitConfig));
      processes.addAll(com.kingsrook.qbits.cdn.processes.CdnProviderProcesses.processes(qBitConfig));
      processes.addAll(com.kingsrook.qbits.cdn.processes.CdnOriginCheckProcesses.processes(qBitConfig));
      processes.addAll(com.kingsrook.qbits.cdn.processes.CdnDefaultsProcesses.processes());
      for(QProcessMetaData process : processes)
      {
         process.setSourceQBitName(qBit.getName());
         output.add(process);
      }
      for(QWidgetMetaData widget : CdnWidgets.widgets())
      {
         output.add(widget);
      }
      output.add(CdnWidgets.app(qBitConfig, tables));
      output.addSelfToInstance(qInstance);
      HealthChecksMetaData.ofOrWithNew(qInstance).withSupplier(new HealthCheckSupplierMetaData()
         .withNamePrefix("cdn.provider").withComponent("cdn").withSupplier(new QCodeReference(CdnProviderHealthChecks.class)));
      com.kingsrook.qbits.cdn.model.CdnHelp.register(qInstance, qBitConfig);

      if(!qBitConfig.getPurgeableTables().isEmpty())
      {
         qInstance.withRecordChangeListener(new QCodeReference(CdnRecordChangeListener.class));
      }
      return (new MetaDataProducerMultiOutput());
   }
}
