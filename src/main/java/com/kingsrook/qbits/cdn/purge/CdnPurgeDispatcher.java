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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnPurgeSource;
import com.kingsrook.qbits.cdn.model.CdnPurgeStatus;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;
import com.kingsrook.qbits.cdn.planning.PathInvalidationPlanner;
import com.kingsrook.qbits.cdn.planning.PathPlan;
import com.kingsrook.qbits.cdn.planning.PathPlannerSettings;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnException;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeKind;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Sends pending purge requests (design spec section 4.5). Per provider:
 ** 1. wait out the flush window (record changes only; actions go at once);
 ** 2. merge the due rows into the oldest one (the others become MERGED);
 ** 3. run the revalidation hooks first (a failure retries later);
 ** 4. plan: tag providers get tags; others get planned paths within the
 **    monthly budget (PathInvalidationPlanner);
 ** 5. send, record the provider ids, and poll IN_PROGRESS rows;
 ** 6. retry with backoff (5 s, 30 s, 2 min, 10 min, 1 h), then DEAD.
 *******************************************************************************/
public class CdnPurgeDispatcher
{
   private static final QLogger      LOG     = QLogger.getLogger(CdnPurgeDispatcher.class);
   private static final ObjectMapper JSON    = new ObjectMapper();
   private static final long[]       BACKOFF = { 5, 30, 120, 600, 3600 };

   private final RevalidationHookRunner hooks;



   /*******************************************************************************
    ** Production wiring.
    *******************************************************************************/
   public CdnPurgeDispatcher()
   {
      this(new RevalidationHookRunner());
   }



   /*******************************************************************************
    ** Constructor with a hook runner (tests).
    *******************************************************************************/
   public CdnPurgeDispatcher(RevalidationHookRunner hooks)
   {
      this.hooks = hooks;
   }



   /*******************************************************************************
    ** One pass over every enabled provider.
    *******************************************************************************/
   public void runOnce() throws QException
   {
      List<QRecord> providers = new QueryAction().execute(new QueryInput(CdnTables.PROVIDER)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords();
      for(QRecord provider : providers)
      {
         try
         {
            dispatch(provider);
            poll(provider);
         }
         catch(Exception e)
         {
            LOG.warn("CDN purge dispatch failed for a provider", e, logPair("provider", provider.getValueString("name")));
         }
      }
   }



   /*******************************************************************************
    ** The adapter config for a provider row.
    *******************************************************************************/
   public static CdnProviderConfig configOf(QRecord provider)
   {
      Map<String, String> settings = Map.of();
      String json = provider.getValueString("settingsJson");
      if(StringUtils.hasContent(json))
      {
         try
         {
            settings = JSON.readValue(json, new TypeReference<Map<String, String>>() {});
         }
         catch(Exception e)
         {
            LOG.warn("Ignoring unreadable provider settings", logPair("provider", provider.getValueString("name")));
         }
      }
      return (new CdnProviderConfig(provider.getValueString("name"), CdnProviderType.valueOf(provider.getValueString("providerType")),
         provider.getValueString("zoneOrDistributionId"), provider.getValueString("secretRef"), settings));
   }



   /*******************************************************************************
    ** Merge, hook, plan and send the due rows of one provider.
    *******************************************************************************/
   void dispatch(QRecord provider) throws QException
   {
      Instant now = CdnRuntime.clock().instant();
      List<QRecord> due = new ArrayList<>();
      for(QRecord row : rows(provider, List.of(CdnPurgeStatus.PENDING.name(), CdnPurgeStatus.FAILED.name())))
      {
         Instant next = ValueUtils.getValueAsInstant(row.getValue("nextAttemptDate"));
         if(next == null || !next.isAfter(now))
         {
            due.add(row);
         }
      }
      if(due.isEmpty())
      {
         return;
      }
      CdnProviderConfig config = configOf(provider);
      int window = config.intSetting("flushWindowSeconds", CdnRuntime.getConfig().getFlushWindowSeconds());
      boolean immediate = due.stream().anyMatch(row -> !CdnPurgeSource.RECORD_CHANGE.name().equals(row.getValueString("source")));
      Instant oldest = due.stream().map(row -> ValueUtils.getValueAsInstant(row.getValue("createDate"))).filter(java.util.Objects::nonNull)
         .min(Comparator.naturalOrder()).orElse(now);
      if(!immediate && window > 0 && oldest.plusSeconds(window).isAfter(now))
      {
         return;
      }

      ///////////////////////////////////////////
      // merge everything due into the oldest //
      ///////////////////////////////////////////
      due.sort(Comparator.comparing(row -> ValueUtils.getValueAsInteger(row.getValue("id"))));
      QRecord master = due.get(0);
      Set<String> tags = new LinkedHashSet<>();
      Set<InvalidationPath> paths = new LinkedHashSet<>();
      boolean includeHubs = false;
      String kind = CdnPurgeKind.TAG.name();
      for(QRecord row : due)
      {
         tags.addAll(PurgeText.tags(row.getValueString("tags")));
         paths.addAll(PurgeText.paths(row.getValueString("paths")));
         includeHubs |= Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(row.getValue("includeHubs")));
         if(CdnPurgeKind.ALL.name().equals(row.getValueString("kind")))
         {
            kind = CdnPurgeKind.ALL.name();
         }
      }
      List<QRecord> updates = new ArrayList<>();
      for(QRecord row : due.subList(1, due.size()))
      {
         updates.add(new QRecord().withValue("id", row.getValue("id")).withValue("status", CdnPurgeStatus.MERGED.name())
            .withValue("mergedIntoId", master.getValue("id")).withValue("modifyDate", now));
      }
      master.setValue("tags", String.join("\n", tags));
      master.setValue("paths", PurgeText.paths(new ArrayList<>(paths)));
      master.setValue("includeHubs", includeHubs);
      master.setValue("kind", kind);

      try
      {
         hooks.run(new ArrayList<>(tags));
         send(provider, config, master, kind, new ArrayList<>(tags), new ArrayList<>(paths), includeHubs, now);
      }
      catch(CdnException e)
      {
         fail(master, e.getMessage(), e.isRetryable(), now);
      }
      catch(QException e)
      {
         fail(master, e.getMessage(), true, now);
      }
      updates.add(master);
      new UpdateAction().execute(new UpdateInput(CdnTables.PURGE_REQUEST).withRecords(updates));
   }



   /*******************************************************************************
    ** Plan and send one merged request; sets its status.
    *******************************************************************************/
   private void send(QRecord provider, CdnProviderConfig config, QRecord master, String kind, List<String> tags, List<InvalidationPath> paths,
      boolean includeHubs, Instant now) throws QException, CdnException
   {
      CdnProviderInterface adapter = CdnRuntime.provider(config.type());
      Set<CdnCapability> capabilities = adapter.getCapabilities(config);
      String callerBase = String.valueOf(master.getValue("id"));
      List<CdnPurgeBatch> batches = new ArrayList<>();
      int billable = 0;

      if(CdnPurgeKind.ALL.name().equals(kind))
      {
         batches.add(new CdnPurgeBatch(CdnPurgeKind.ALL, List.of("/*"), callerBase));
         billable = 1;
      }
      else if(capabilities.contains(CdnCapability.PURGE_TAG) && !tags.isEmpty())
      {
         batches.add(new CdnPurgeBatch(CdnPurgeKind.TAG, tags, callerBase));
      }
      else
      {
         PathPlan plan = new PathInvalidationPlanner(PathPlannerSettings.from(config)).plan(paths, includeHubs, billedThisMonth(provider, now));
         master.setValue("billablePaths", plan.billablePaths());
         if(plan.band() == PathPlan.Band.NOTHING)
         {
            done(master, CdnPurgeStatus.SKIPPED_HUB_ONLY, null, now);
            return;
         }
         if(plan.band() == PathPlan.Band.DEFERRED)
         {
            done(master, CdnPurgeStatus.DEFERRED_BUDGET, null, now);
            return;
         }
         int i = 0;
         for(List<String> batch : plan.batches())
         {
            batches.add(new CdnPurgeBatch(CdnPurgeKind.PATHS, batch, plan.batches().size() == 1 ? callerBase : callerBase + "-" + (i++)));
         }
         master.setValue("paths", String.join("\n", plan.allPaths()));
         billable = plan.billablePaths();
      }
      if(billable > 0)
      {
         master.setValue("billablePaths", billable);
      }

      List<String> ids = new ArrayList<>();
      boolean inProgress = false;
      boolean skipped = true;
      for(CdnPurgeBatch batch : batches)
      {
         CdnPurgeResult result = adapter.purge(config, batch);
         switch(result.state())
         {
            case IN_PROGRESS -> inProgress = true;
            case FAILED_RETRYABLE -> throw (new CdnException(result.message(), true, null));
            case FAILED_PERMANENT -> throw (new CdnException(result.message(), false, null));
            default ->
            {
            }
         }
         skipped &= result.state() == CdnPurgeResult.State.SKIPPED;
         if(result.providerRequestId() != null)
         {
            ids.add(result.providerRequestId());
         }
      }
      master.setValue("providerRequestId", ids.isEmpty() ? null : String.join(",", ids));
      master.setValue("sentDate", now);
      master.setValue("attempts", attempts(master) + 1);
      if(skipped)
      {
         done(master, CdnPurgeStatus.SKIPPED_NONE_PROVIDER, null, now);
      }
      else if(inProgress)
      {
         master.setValue("status", CdnPurgeStatus.IN_PROGRESS.name());
         master.setValue("modifyDate", now);
      }
      else
      {
         done(master, CdnPurgeStatus.SUCCEEDED, null, now);
      }
   }



   /*******************************************************************************
    ** Poll IN_PROGRESS rows of one provider.
    *******************************************************************************/
   void poll(QRecord provider) throws QException
   {
      List<QRecord> rows = rows(provider, List.of(CdnPurgeStatus.IN_PROGRESS.name()));
      if(rows.isEmpty())
      {
         return;
      }
      CdnProviderConfig config = configOf(provider);
      CdnProviderInterface adapter = CdnRuntime.provider(config.type());
      Instant now = CdnRuntime.clock().instant();
      List<QRecord> updates = new ArrayList<>();
      for(QRecord row : rows)
      {
         boolean allDone = true;
         try
         {
            for(String id : row.getValueString("providerRequestId") == null ? new String[0] : row.getValueString("providerRequestId").split(","))
            {
               allDone &= adapter.checkPurge(config, id).state() == CdnPurgeResult.State.SUCCEEDED;
            }
         }
         catch(CdnException e)
         {
            allDone = false;
            LOG.info("Could not check a CDN purge yet", logPair("purgeRequestId", row.getValue("id")));
         }
         if(allDone)
         {
            QRecord update = new QRecord().withValue("id", row.getValue("id"));
            done(update, CdnPurgeStatus.SUCCEEDED, null, now);
            updates.add(update);
         }
      }
      if(!updates.isEmpty())
      {
         new UpdateAction().execute(new UpdateInput(CdnTables.PURGE_REQUEST).withRecords(updates));
      }
   }



   /*******************************************************************************
    ** Paths billed this UTC month for a provider.
    *******************************************************************************/
   static int billedThisMonth(QRecord provider, Instant now) throws QException
   {
      LocalDate first = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
      List<QRecord> sent = new QueryAction().execute(new QueryInput(CdnTables.PURGE_REQUEST).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, provider.getValue("id")))
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.IN, List.of(CdnPurgeStatus.SUCCEEDED.name(), CdnPurgeStatus.IN_PROGRESS.name())))
         .withCriteria(new QFilterCriteria("sentDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, first.atStartOfDay(ZoneOffset.UTC).toInstant())))).getRecords();
      int total = 0;
      for(QRecord row : sent)
      {
         Integer paths = ValueUtils.getValueAsInteger(row.getValue("billablePaths"));
         total += paths == null ? 0 : paths;
      }
      return (total);
   }



   /*******************************************************************************
    ** Rows of a provider in some statuses, oldest first.
    *******************************************************************************/
   private static List<QRecord> rows(QRecord provider, List<Serializable> statuses) throws QException
   {
      return (new QueryAction().execute(new QueryInput(CdnTables.PURGE_REQUEST).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, provider.getValue("id")))
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.IN, statuses))
         .withOrderBy(new QFilterOrderBy("id")))).getRecords());
   }



   /*******************************************************************************
    ** A failed attempt: FAILED with backoff, or DEAD.
    *******************************************************************************/
   void fail(QRecord row, String message, boolean retryable, Instant now)
   {
      int attempts = attempts(row) + 1;
      row.setValue("attempts", attempts);
      row.setValue("errorMessage", message == null ? "Purge failed" : message);
      row.setValue("modifyDate", now);
      if(!retryable || attempts >= CdnRuntime.getConfig().getMaxAttempts())
      {
         row.setValue("status", CdnPurgeStatus.DEAD.name());
         row.setValue("completedDate", now);
      }
      else
      {
         row.setValue("status", CdnPurgeStatus.FAILED.name());
         row.setValue("nextAttemptDate", now.plus(Duration.ofSeconds(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)])));
      }
   }



   /*******************************************************************************
    ** A final status.
    *******************************************************************************/
   private static void done(QRecord row, CdnPurgeStatus status, String message, Instant now)
   {
      row.setValue("status", status.name());
      row.setValue("completedDate", now);
      row.setValue("modifyDate", now);
      if(message != null)
      {
         row.setValue("errorMessage", message);
      }
   }



   /*******************************************************************************
    ** Attempts so far.
    *******************************************************************************/
   private static int attempts(QRecord row)
   {
      Integer attempts = ValueUtils.getValueAsInteger(row.getValue("attempts"));
      return (attempts == null ? 0 : attempts);
   }
}
