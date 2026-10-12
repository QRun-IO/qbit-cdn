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


import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.headers.CdnResponseHeaderPolicy;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import java.util.Set;


/*******************************************************************************
 ** A memory-backend instance with the qbit installed, a "blog" and a "post"
 ** table (post is purgeable), and fake providers that record what they send.
 *******************************************************************************/
public final class CdnTestSupport
{
   public static final String  MEMORY = "memory";
   public static Instant       NOW    = Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnTestSupport()
   {
   }



   /*******************************************************************************
    ** Fresh instance, store, clock and fakes.
    *******************************************************************************/
   public static QInstance setUp() throws QException
   {
      NOW = Instant.now().plusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      MemoryRecordStore.getInstance().reset();
      CdnResponseHeaderPolicy.clearCache();
      CdnRuntime.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
      FakePathProvider.reset();
      FakeTagProvider.reset();

      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName(MEMORY).withBackendType(MemoryBackendModule.class));
      instance.addTable(new QTableMetaData().withName("blog").withBackendName(MEMORY).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("slug", QFieldType.STRING)));
      instance.addTable(new QTableMetaData().withName("post").withBackendName(MEMORY).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("slug", QFieldType.STRING))
         .withField(new QFieldMetaData("title", QFieldType.STRING))
         .withField(new QFieldMetaData("blogId", QFieldType.INTEGER))
         .withField(new QFieldMetaData("isPublished", QFieldType.BOOLEAN)));
      new CdnQBitProducer().withQBitConfig(new CdnQBitConfig().withBackendName(MEMORY).withPurgeableTables(List.of("post"))
         .withOpenWhenAnonymous(true).withFlushWindowSeconds(30)).produce(instance);
      QContext.init(instance, new QSession());
      CdnRuntime.registerProvider(CdnProviderType.CLOUDFRONT, FakePathProvider::new);
      CdnRuntime.registerProvider(CdnProviderType.FASTLY, FakeTagProvider::new);
      return (instance);
   }



   /*******************************************************************************
    ** Insert rows.
    *******************************************************************************/
   public static List<QRecord> insert(String table, QRecord... records) throws QException
   {
      return (new InsertAction().execute(new InsertInput(table).withRecords(List.of(records))).getRecords());
   }



   /*******************************************************************************
    ** A provider with one LIVE hostname; returns the provider id.
    *******************************************************************************/
   public static Integer liveProvider(CdnProviderType type, String settingsJson) throws QException
   {
      Integer id = (Integer) insert(CdnTables.PROVIDER, new QRecord().withValue("name", type.name().toLowerCase()).withValue("providerType", type.name())
         .withValue("enabled", true).withValue("zoneOrDistributionId", "E123").withValue("settingsJson", settingsJson)).get(0).getValue("id");
      insert(CdnTables.HOSTNAME, new QRecord().withValue("hostname", "www.example.com").withValue("cdnProviderId", id).withValue("stage", "LIVE")
         .withValue("purgeEnabled", true));
      return (id);
   }



   /*******************************************************************************
    ** The post rule plus one blog.
    *******************************************************************************/
   public static void postRule() throws QException
   {
      insert("blog", new QRecord().withValue("slug", "quick-bits"));
      insert(CdnTables.TABLE_TAG_RULE, new QRecord().withValue("tableName", "post").withValue("enabled", true)
         .withValue("tagTemplates", "post:{id}\nblog:{blogId}:list")
         .withValue("invalidationPaths", "/blog/{blog.slug}/{slug}*\n/v1/posts/{id}*\nhub:/blog/{blog.slug}")
         .withValue("publishedField", "isPublished")
         .withValue("onlyWhenFieldsChange", "slug,title,isPublished"));
   }



   /*******************************************************************************
    ** Path-only provider (CloudFront-like): records batches, answers
    ** IN_PROGRESS, completes on check.
    *******************************************************************************/
   public static class FakePathProvider implements CdnProviderInterface
   {
      public static final List<CdnPurgeBatch> SENT = new ArrayList<>();
      public static CdnException              failure;



      /*******************************************************************************
       ** Reset recorded state.
       *******************************************************************************/
      public static void reset()
      {
         SENT.clear();
         failure = null;
      }



      /*******************************************************************************
       ** Getter for type.
       *******************************************************************************/
      @Override
      public CdnProviderType getType()
      {
         return (CdnProviderType.CLOUDFRONT);
      }



      /*******************************************************************************
       ** Paths and wildcards only.
       *******************************************************************************/
      @Override
      public Set<CdnCapability> getCapabilities(CdnProviderConfig config)
      {
         return (Set.of(CdnCapability.PURGE_URL, CdnCapability.PURGE_WILDCARD, CdnCapability.PURGE_ALL));
      }



      /*******************************************************************************
       ** No tag header.
       *******************************************************************************/
      @Override
      public CdnHeaderDialect getHeaderDialect()
      {
         return (CdnHeaderDialect.NO_TAGS);
      }



      /*******************************************************************************
       ** Record, or fail.
       *******************************************************************************/
      @Override
      public CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch) throws CdnException
      {
         if(failure != null)
         {
            throw (failure);
         }
         SENT.add(batch);
         return (new CdnPurgeResult(CdnPurgeResult.State.IN_PROGRESS, "I-" + batch.callerReference(), 201, null));
      }



      /*******************************************************************************
       ** Always done when checked.
       *******************************************************************************/
      @Override
      public CdnPurgeResult checkPurge(CdnProviderConfig config, String providerRequestId)
      {
         return (CdnPurgeResult.succeeded(providerRequestId));
      }
   }



   /*******************************************************************************
    ** Tag provider (Fastly-like): synchronous.
    *******************************************************************************/
   public static class FakeTagProvider implements CdnProviderInterface
   {
      public static final List<CdnPurgeBatch> SENT = new ArrayList<>();



      /*******************************************************************************
       ** Reset recorded state.
       *******************************************************************************/
      public static void reset()
      {
         SENT.clear();
      }



      /*******************************************************************************
       ** Getter for type.
       *******************************************************************************/
      @Override
      public CdnProviderType getType()
      {
         return (CdnProviderType.FASTLY);
      }



      /*******************************************************************************
       ** Tags.
       *******************************************************************************/
      @Override
      public Set<CdnCapability> getCapabilities(CdnProviderConfig config)
      {
         return (Set.of(CdnCapability.PURGE_TAG, CdnCapability.HONORS_CDN_CACHE_CONTROL));
      }



      /*******************************************************************************
       ** Surrogate-Key.
       *******************************************************************************/
      @Override
      public CdnHeaderDialect getHeaderDialect()
      {
         return (new CdnHeaderDialect("Surrogate-Key", " ", false, true));
      }



      /*******************************************************************************
       ** Record.
       *******************************************************************************/
      @Override
      public CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch)
      {
         SENT.add(batch);
         return (CdnPurgeResult.succeeded(null));
      }
   }
}
