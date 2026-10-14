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


import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnPurgeStatus;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.planning.PathPlannerSettings;
import com.kingsrook.qbits.cdn.purge.CdnPurgeDispatcher;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Everything the CDN home shows, read from the qbit's tables (real data
 ** only; empty lists mean an empty state, never a placeholder number).
 *******************************************************************************/
public record CdnHomeData(List<ProviderView> providers, List<HostnameView> hostnames, List<StatView> stats, List<QRecord> recentPurges,
                          int failed24h, int dead24h, int failed7d, int dead7d, List<BudgetView> budgets, List<QRecord> originChecks,
                          Map<String, Integer> openDriftByArea, Instant lastDriftCheck, Instant now)
{
   public static final int ROTATION_WARNING_DAYS = 75;
   public static final int ROTATION_DAYS         = 90;



   /*******************************************************************************
    ** A provider row as shown.
    *******************************************************************************/
   public record ProviderView(Integer id, String name, String type, String distributionId, String dnsTarget, String originHost,
                              String lastHealthStatus, String lastHealthMessage, Instant lastSuccessfulTest, Long credentialAgeDays, Boolean enabled)
   {
      /*******************************************************************************
       ** Whether this is a real CDN (not the NONE placeholder).
       *******************************************************************************/
      public boolean isConnected()
      {
         return (Boolean.TRUE.equals(enabled) && !CdnProviderType.NONE.name().equals(type));
      }
   }



   /*******************************************************************************
    ** A hostname row as shown.
    *******************************************************************************/
   public record HostnameView(String hostname, String stage, String provider)
   {
   }



   /*******************************************************************************
    ** Edge statistics totals for one provider over 7 and 30 days.
    *******************************************************************************/
   public record StatView(String provider, long requests7d, long bytes7d, BigDecimal hitRate7d, BigDecimal error5xx7d, long requests30d, long bytes30d,
                          List<Long> dailyRequests)
   {
   }



   /*******************************************************************************
    ** Month-to-date invalidation budget for a path-based provider.
    *******************************************************************************/
   public record BudgetView(String provider, int billed, int soft, int hard, int free, int projected)
   {
   }



   /*******************************************************************************
    ** Load from the tables.
    *******************************************************************************/
   public static CdnHomeData load() throws QException
   {
      Instant now = CdnRuntime.clock().instant();
      LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
      Map<Integer, String> providerNames = new LinkedHashMap<>();
      List<ProviderView> providers = new ArrayList<>();
      List<BudgetView> budgets = new ArrayList<>();
      for(QRecord row : query(CdnTables.PROVIDER, new QQueryFilter().withOrderBy(new QFilterOrderBy("id"))))
      {
         Integer id = ValueUtils.getValueAsInteger(row.getValue("id"));
         providerNames.put(id, row.getValueString("name"));
         LocalDate rotated = ValueUtils.getValueAsLocalDate(row.getValue("credentialRotatedDate"));
         providers.add(new ProviderView(id, row.getValueString("name"), row.getValueString("providerType"), row.getValueString("zoneOrDistributionId"),
            row.getValueString("dnsTarget"), row.getValueString("originHost"), row.getValueString("lastHealthStatus"), row.getValueString("lastHealthMessage"),
            ValueUtils.getValueAsInstant(row.getValue("lastSuccessfulTestDate")), rotated == null ? null : ChronoUnit.DAYS.between(rotated, today), row.getValueBoolean("enabled")));
         if(CdnProviderType.CLOUDFRONT.name().equals(row.getValueString("providerType")))
         {
            PathPlannerSettings settings = PathPlannerSettings.from(CdnPurgeDispatcher.configOf(row));
            int billed = billed(id, today);
            int day = today.getDayOfMonth();
            int projected = day == 0 ? billed : (int) Math.round(billed * (double) today.lengthOfMonth() / day);
            budgets.add(new BudgetView(row.getValueString("name"), billed, settings.softPathLimit(), settings.hardPathLimit(), settings.freePathsPerMonth(), projected));
         }
      }

      List<HostnameView> hostnames = new ArrayList<>();
      for(QRecord row : query(CdnTables.HOSTNAME, new QQueryFilter().withOrderBy(new QFilterOrderBy("hostname"))))
      {
         hostnames.add(new HostnameView(row.getValueString("hostname"), row.getValueString("stage"),
            providerNames.get(ValueUtils.getValueAsInteger(row.getValue("cdnProviderId")))));
      }

      List<StatView> stats = new ArrayList<>();
      LocalDate from30 = today.minusDays(30);
      LocalDate from7 = today.minusDays(7);
      List<QRecord> statRows = query(CdnTables.EDGE_STAT_DAILY, new QQueryFilter(new QFilterCriteria("statDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, from30))
         .withOrderBy(new QFilterOrderBy("statDate")));
      for(Map.Entry<Integer, String> provider : providerNames.entrySet())
      {
         List<QRecord> mine = statRows.stream().filter(row -> provider.getKey().equals(ValueUtils.getValueAsInteger(row.getValue("cdnProviderId")))).toList();
         if(mine.isEmpty())
         {
            continue;
         }
         long req7 = 0;
         long bytes7 = 0;
         long req30 = 0;
         long bytes30 = 0;
         BigDecimal hitSum = BigDecimal.ZERO;
         int hitDays = 0;
         BigDecimal errSum = BigDecimal.ZERO;
         int errDays = 0;
         List<Long> daily = new ArrayList<>();
         for(QRecord row : mine)
         {
            LocalDate date = ValueUtils.getValueAsLocalDate(row.getValue("statDate"));
            long requests = nz(ValueUtils.getValueAsLong(row.getValue("requests")));
            long bytes = nz(ValueUtils.getValueAsLong(row.getValue("bytesDownloaded")));
            req30 += requests;
            bytes30 += bytes;
            daily.add(requests);
            if(date != null && date.isAfter(from7))
            {
               req7 += requests;
               bytes7 += bytes;
               BigDecimal hit = ValueUtils.getValueAsBigDecimal(row.getValue("cacheHitRate"));
               if(hit != null)
               {
                  hitSum = hitSum.add(hit);
                  hitDays++;
               }
               BigDecimal err = ValueUtils.getValueAsBigDecimal(row.getValue("error5xxRate"));
               if(err != null)
               {
                  errSum = errSum.add(err);
                  errDays++;
               }
            }
         }
         stats.add(new StatView(provider.getValue(), req7, bytes7, hitDays == 0 ? null : hitSum.divide(BigDecimal.valueOf(hitDays), 2, java.math.RoundingMode.HALF_UP),
            errDays == 0 ? null : errSum.divide(BigDecimal.valueOf(errDays), 2, java.math.RoundingMode.HALF_UP), req30, bytes30, daily));
      }

      List<QRecord> recent = new QueryAction().execute(new QueryInput(CdnTables.PURGE_REQUEST)
         .withFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy("id", false)).withLimit(10))).getRecords();
      Instant since7 = now.minus(7, ChronoUnit.DAYS);
      Instant since24 = now.minus(24, ChronoUnit.HOURS);
      int failed24 = 0;
      int dead24 = 0;
      int failed7 = 0;
      int dead7 = 0;
      for(QRecord row : query(CdnTables.PURGE_REQUEST, new QQueryFilter()
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.IN, List.of(CdnPurgeStatus.FAILED.name(), CdnPurgeStatus.DEAD.name())))
         .withCriteria(new QFilterCriteria("modifyDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, since7))))
      {
         boolean dead = CdnPurgeStatus.DEAD.name().equals(row.getValueString("status"));
         Instant modified = ValueUtils.getValueAsInstant(row.getValue("modifyDate"));
         boolean recentDay = modified != null && !modified.isBefore(since24);
         if(dead)
         {
            dead7++;
            dead24 += recentDay ? 1 : 0;
         }
         else
         {
            failed7++;
            failed24 += recentDay ? 1 : 0;
         }
      }

      Map<String, QRecord> latestOrigin = new LinkedHashMap<>();
      for(QRecord row : query(CdnTables.ORIGIN_CHECK, new QQueryFilter().withOrderBy(new QFilterOrderBy("checkDate"))))
      {
         latestOrigin.put(row.getValueString("scope"), row);
      }

      Map<String, Integer> drift = new LinkedHashMap<>();
      Instant lastDrift = null;
      for(QRecord row : query(CdnTables.DRIFT_FINDING, new QQueryFilter()))
      {
         Instant checked = ValueUtils.getValueAsInstant(row.getValue("checkedDate"));
         if(checked != null && (lastDrift == null || checked.isAfter(lastDrift)))
         {
            lastDrift = checked;
         }
         if(row.getValue("resolvedDate") == null)
         {
            drift.merge(row.getValueString("area"), 1, Integer::sum);
         }
      }

      return (new CdnHomeData(providers, hostnames, stats, recent, failed24, dead24, failed7, dead7, budgets, new ArrayList<>(latestOrigin.values()),
         drift, lastDrift, now));
   }



   /*******************************************************************************
    ** Whether any real CDN is configured.
    *******************************************************************************/
   public boolean isConnected()
   {
      return (providers.stream().anyMatch(ProviderView::isConnected));
   }



   /*******************************************************************************
    ** Overall state for the Health card: NOT_CONNECTED, ATTENTION or OK.
    *******************************************************************************/
   public String overallState()
   {
      if(!isConnected())
      {
         return ("NOT_CONNECTED");
      }
      boolean attention = dead24h > 0 || !openDriftByArea.isEmpty()
         || providers.stream().anyMatch(p -> p.isConnected() && ("FAILED".equals(p.lastHealthStatus())
            || (p.credentialAgeDays() != null && p.credentialAgeDays() >= ROTATION_WARNING_DAYS)))
         || budgets.stream().anyMatch(b -> b.billed() > b.soft());
      return (attention ? "ATTENTION" : "OK");
   }



   /*******************************************************************************
    ** Paths billed this month for a provider.
    *******************************************************************************/
   private static int billed(Integer providerId, LocalDate today) throws QException
   {
      int total = 0;
      for(QRecord row : query(CdnTables.PURGE_REQUEST, new QQueryFilter()
         .withCriteria(new QFilterCriteria("cdnProviderId", QCriteriaOperator.EQUALS, providerId))
         .withCriteria(new QFilterCriteria("status", QCriteriaOperator.IN, List.of(CdnPurgeStatus.SUCCEEDED.name(), CdnPurgeStatus.IN_PROGRESS.name())))
         .withCriteria(new QFilterCriteria("sentDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()))))
      {
         Integer paths = ValueUtils.getValueAsInteger(row.getValue("billablePaths"));
         total += paths == null ? 0 : paths;
      }
      return (total);
   }



   /*******************************************************************************
    ** Query helper.
    *******************************************************************************/
   private static List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      return (new QueryAction().execute(new QueryInput(table).withFilter(filter)).getRecords());
   }



   /*******************************************************************************
    ** Zero for null.
    *******************************************************************************/
   private static long nz(Long value)
   {
      return (value == null ? 0 : value);
   }



   /*******************************************************************************
    ** Newest first (for callers that re-sort).
    *******************************************************************************/
   public static Comparator<QRecord> newestFirst()
   {
      return (Comparator.comparing((QRecord row) -> ValueUtils.getValueAsInteger(row.getValue("id"))).reversed());
   }
}
