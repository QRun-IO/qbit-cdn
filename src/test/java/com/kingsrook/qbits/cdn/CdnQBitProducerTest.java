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


import com.kingsrook.qbits.cdn.app.CdnWidgets;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.processes.CdnProcesses;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The producer adds every table, process, widget and the app, and the
 ** resulting instance validates.
 *******************************************************************************/
class CdnQBitProducerTest
{
   /*******************************************************************************
    ** Everything is produced and the instance is valid.
    *******************************************************************************/
   @Test
   void producesAValidInstance() throws Exception
   {
      QInstance instance = CdnTestSupport.setUp();
      for(String table : CdnTables.ALL)
      {
         assertThat(instance.getTable(table)).as(table).isNotNull();
         assertThat(table).startsWith("cdn_").isLowerCase();
      }
      assertThat(instance.getProcess(CdnProcesses.PURGE_PREFIX + "post")).isNotNull();
      assertThat(instance.getProcess(CdnProcesses.PURGE_PREFIX + "post").getTableName()).isEqualTo("post");
      assertThat(instance.getProcess(CdnProcesses.RETRY_PURGE)).isNotNull();
      assertThat(instance.getProcess(CdnProcesses.PURGE_HOSTNAME)).isNotNull();
      assertThat(instance.getProcess(CdnProcesses.DISPATCH_PURGES).getIsHidden()).isTrue();
      assertThat(instance.getWidget(CdnWidgets.HOME_WIDGET)).isNotNull();
      assertThat(instance.getApp("cdnAdmin").getSections()).extracting(section -> section.getLabel())
         .containsExactly("Connection", "Settings", "Activity", "Performance");
      assertThat(instance.getRecordChangeListeners()).isNotEmpty();
      new QInstanceValidator().validate(instance);

      QInstance customized = new QInstance();
      customized.addBackend(new com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData().withName("memory")
         .withBackendType(com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule.class));
      new CdnQBitProducer().withQBitConfig(new CdnQBitConfig().withBackendName("memory")
         .withTableMetaDataCustomizer((qInstance, table) -> table.withLabel("Custom " + table.getLabel()))).produce(customized);
      assertThat(customized.getTable(CdnTables.PROVIDER).getLabel()).isEqualTo("Custom CDN Provider");
      assertThat(new CdnQBitConfig().getAppPath()).isEqualTo("/cdnAdmin");
      assertThat(new CdnQBitConfig().withAppPath("/Website/cdnAdmin").getAppPath()).isEqualTo("/Website/cdnAdmin");
      assertThat(new CdnQBitConfig().getTablePathPrefix()).isEqualTo("/cdnAdmin");
      assertThat(new CdnQBitConfig().withTablePathPrefix("/app").getTablePathPrefix()).isEqualTo("/app");
   }



   /*******************************************************************************
    ** A config without a backend is rejected.
    *******************************************************************************/
   @Test
   void rejectsInvalidConfig()
   {
      assertThatThrownBy(() -> new CdnQBitProducer().withQBitConfig(new CdnQBitConfig()).produce(new QInstance()))
         .isInstanceOf(QException.class).hasMessageContaining("backendName");
      CdnQBitProducer producer = new CdnQBitProducer().withNamespace("x");
      assertThat(producer.isEnabled()).isFalse();
      assertThat(producer.getNamespace()).isEqualTo("x");
   }
}
