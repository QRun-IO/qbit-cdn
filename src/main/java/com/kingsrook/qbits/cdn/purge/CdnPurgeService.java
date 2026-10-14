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

package com.kingsrook.qbits.cdn.purge;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnHostnameStage;
import com.kingsrook.qbits.cdn.model.CdnPurgeSource;
import com.kingsrook.qbits.cdn.model.CdnPurgeStatus;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Records purge requests. One row per enabled provider: PENDING when the
 ** provider has a live hostname (LIVE or LOCKED with purge enabled),
 ** SKIPPED_NONE_PROVIDER for the NONE provider, otherwise SKIPPED_STAGE_OFF,
 ** so the would-be purges stay visible before a cutover.
 *******************************************************************************/
public final class CdnPurgeService
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnPurgeService()
   {
   }



   /*******************************************************************************
    ** What to purge and why.
    *******************************************************************************/
   public record Request(String kind, List<String> tags, List<InvalidationPath> paths, boolean includeHubs, CdnPurgeSource source,
                         String sourceTable, Serializable sourceRecordId)
   {
   }



   /*******************************************************************************
    ** Insert one purge request row per enabled provider; returns them.
    *******************************************************************************/
   public static List<QRecord> enqueue(Request request) throws QException
   {
      return (enqueueFor(null, request));
   }



   /*******************************************************************************
    ** Insert a purge request row for one provider (or every enabled provider
    ** when providerId is null); returns them.
    *******************************************************************************/
   public static List<QRecord> enqueueFor(Serializable providerId, Request request) throws QException
   {
      List<QRecord> rows = new ArrayList<>();
      if(!CdnRuntime.isInstalled())
      {
         return (rows);
      }
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true));
      if(providerId != null)
      {
         filter.addCriteria(new QFilterCriteria("id", QCriteriaOperator.EQUALS, providerId));
      }
      List<QRecord> providers = new QueryAction().execute(new QueryInput(CdnTables.PROVIDER).withFilter(filter)).getRecords();
      String requestedBy = QContext.getQSession() == null || QContext.getQSession().getUser() == null ? null : QContext.getQSession().getUser().getIdReference();
      for(QRecord provider : providers)
      {
         rows.add(new QRecord()
            .withValue("cdnProviderId", provider.getValue("id"))
            .withValue("kind", request.kind())
            .withValue("tags", String.join("\n", request.tags()))
            .withValue("paths", PurgeText.paths(request.paths()))
            .withValue("includeHubs", request.includeHubs())
            .withValue("source", request.source().name())
            .withValue("sourceTable", request.sourceTable())
            .withValue("sourceRecordId", request.sourceRecordId() == null ? null : String.valueOf(request.sourceRecordId()))
            .withValue("requestedBy", requestedBy)
            .withValue("status", initialStatus(provider).name())
            .withValue("attempts", 0)
            .withValue("createDate", CdnRuntime.clock().instant())
            .withValue("modifyDate", CdnRuntime.clock().instant()));
      }
      if(rows.isEmpty())
      {
         return (rows);
      }
      return (new InsertAction().execute(new InsertInput(CdnTables.PURGE_REQUEST).withRecords(rows)).getRecords());
   }



   /*******************************************************************************
    ** Sending eligibility for one provider, or all enabled providers when null.
    ** A pending status means at least one provider can send; otherwise the
    ** status gives the same reason used when recording a new request.
    *******************************************************************************/
   public static CdnPurgeStatus sendingStatus(Serializable providerId) throws QException
   {
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true));
      if(providerId != null)
      {
         filter.addCriteria(new QFilterCriteria("id", QCriteriaOperator.EQUALS, providerId));
      }
      CdnPurgeStatus status = CdnPurgeStatus.SKIPPED_NONE_PROVIDER;
      for(QRecord provider : new QueryAction().execute(new QueryInput(CdnTables.PROVIDER).withFilter(filter)).getRecords())
      {
         CdnPurgeStatus candidate = initialStatus(provider);
         if(candidate == CdnPurgeStatus.PENDING)
         {
            return (candidate);
         }
         if(candidate == CdnPurgeStatus.SKIPPED_STAGE_OFF)
         {
            status = candidate;
         }
      }
      return (status);
   }



   /*******************************************************************************
    ** Status of a new request for a provider.
    *******************************************************************************/
   static CdnPurgeStatus initialStatus(QRecord provider) throws QException
   {
      if(CdnProviderType.NONE.name().equals(provider.getValueString("providerType")))
      {
         return (CdnPurgeStatus.SKIPPED_NONE_PROVIDER);
      }
      List<QRecord> live = new QueryAction().execute(new QueryInput(CdnTables.HOSTNAME).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, provider.getValue("id")))
         .withCriteria(new QFilterCriteria("stage", QCriteriaOperator.IN, List.of(CdnHostnameStage.LIVE.name(), CdnHostnameStage.LOCKED.name())))
         .withCriteria(new QFilterCriteria("purgeEnabled", QCriteriaOperator.EQUALS, true)))).getRecords();
      return (live.isEmpty() ? CdnPurgeStatus.SKIPPED_STAGE_OFF : CdnPurgeStatus.PENDING);
   }
}
