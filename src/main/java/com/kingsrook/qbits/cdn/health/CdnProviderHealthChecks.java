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

package com.kingsrook.qbits.cdn.health;


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.purge.CdnPurgeDispatcher;
import com.kingsrook.qbits.cdn.spi.CdnHealth;
import com.kingsrook.qbits.cdn.spi.CdnHealthProbe;
import com.kingsrook.qbits.cdn.spi.CdnOperations;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplier;
import com.kingsrook.qqq.backend.core.health.HealthDetails;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** Central health checks: an adapter's own health probe when it has one
 ** (CloudFront), else the read-only connection test, else UP/not testable.
 *******************************************************************************/
public class CdnProviderHealthChecks implements HealthCheckSupplier
{
   /*******************************************************************************
    ** Resolve the enabled provider rows each time the registry refreshes.
    *******************************************************************************/
   @Override
   public List<HealthCheckMetaData> supply(QInstance qInstance) throws QException
   {
      List<HealthCheckMetaData> checks = new ArrayList<>();
      for(QRecord provider : new QueryAction().execute(new QueryInput(CdnTables.PROVIDER)
         .withFilter(new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords())
      {
         Serializable id = provider.getValue("id");
         String name = "cdn.provider." + id.toString().toLowerCase(Locale.ROOT);
         checks.add(new HealthCheckMetaData().withName(name).withLabel(name).withComponent("cdn")
            .withKind(HealthCheckKind.DEPENDENCY).withCategory(HealthCategory.CDN)
            .withIntervalSeconds(900).withTimeoutMs(20000).withCheckInstance(input -> run(id, input)));
      }
      return (checks);
   }



   /*******************************************************************************
    ** Reload configuration so disabled or deleted providers are not probed.
    ** Never copy provider messages, addresses or exception text into outcomes.
    *******************************************************************************/
   private static HealthCheckOutcome run(Serializable id, HealthCheckInput input) throws QException
   {
      if(!Instant.now().isBefore(input.getDeadline()))
      {
         return (HealthCheckOutcome.down("CDN connection test timed out"));
      }
      QRecord provider = new GetAction().executeForRecord(new GetInput(CdnTables.PROVIDER).withPrimaryKey(id));
      if(provider == null || !Boolean.TRUE.equals(provider.getValueBoolean("enabled")))
      {
         return (HealthCheckOutcome.unknown("CDN provider is disabled or removed"));
      }
      CdnProviderConfig config = CdnPurgeDispatcher.configOf(provider);
      CdnProviderInterface adapter = CdnRuntime.provider(config.type());
      if(adapter instanceof CdnHealthProbe healthProbe)
      {
         return (bounded(() -> fromProbe(healthProbe.checkHealth(config), config), input));
      }
      if(adapter instanceof CdnOperations operations)
      {
         return (bounded(() -> fromConnectionTest(operations.connectionTest(config), config), input));
      }
      return (notTestable(config));
   }



   /*******************************************************************************
    ** An adapter with nothing to probe (the NONE placeholder, or one without a
    ** connection test) is UP and says it is not testable. UNKNOWN would sit on
    ** the dashboard forever: the central evaluator never alerts or changes
    ** state on UNKNOWN.
    *******************************************************************************/
   private static HealthCheckOutcome notTestable(CdnProviderConfig config)
   {
      boolean none = config.type() == CdnProviderType.NONE;
      return (HealthCheckOutcome.up(none ? "No CDN configured; purges are recorded only" : "CDN provider configured; not testable (no connection test)")
         .withDetails(new HealthDetails().withValue("providerType", config.type().name()).withValue("testable", false)
            .withValue("reason", none ? "no CDN provider" : "provider type has no connection test")));
   }



   /*******************************************************************************
    ** Map an adapter health probe. Its contract (CdnHealth) is fixed messages
    ** and safe details only.
    *******************************************************************************/
   private static HealthCheckOutcome fromProbe(CdnHealth health, CdnProviderConfig config)
   {
      HealthCheckOutcome outcome = switch(health.state())
      {
         case UP -> HealthCheckOutcome.up(health.message());
         case DEGRADED -> HealthCheckOutcome.degraded(health.message());
         case DOWN -> HealthCheckOutcome.down(health.message());
      };
      HealthDetails details = new HealthDetails().withValue("providerType", config.type().name());
      health.details().forEach(details::withValue);
      return (outcome.withDetails(details));
   }



   /*******************************************************************************
    ** Map a provider-neutral connection test.
    *******************************************************************************/
   private static HealthCheckOutcome fromConnectionTest(CdnOperations.ConnectionTest test, CdnProviderConfig config)
   {
      HealthCheckOutcome outcome = !test.ok() ? HealthCheckOutcome.down("CDN connection test failed")
         : !test.originHeaderConfigured() ? HealthCheckOutcome.degraded("CDN origin header is not configured")
         : HealthCheckOutcome.up("CDN connection test passed");
      // Only known provider states are safe; arbitrary status text may contain secrets.
      String status = "Deployed".equals(test.status()) ? "Deployed" : "InProgress".equals(test.status()) ? "InProgress" : "UNKNOWN";
      return (outcome.withDetails(new HealthDetails().withValue("providerType", config.type().name())
         .withValue("status", status).withValue("originHeaderConfigured", test.originHeaderConfigured())));
   }



   /*******************************************************************************
    ** The SPI has no timeout parameter. Bound its wait by the remaining
    ** deadline and interrupt the probe on timeout or runner cancellation.
    *******************************************************************************/
   private static HealthCheckOutcome bounded(Callable<HealthCheckOutcome> probe, HealthCheckInput input)
   {
      long remaining = Duration.between(Instant.now(), input.getDeadline()).toNanos();
      if(remaining <= 0)
      {
         return (HealthCheckOutcome.down("CDN connection test timed out"));
      }
      FutureTask<HealthCheckOutcome> task = new FutureTask<>(probe);
      Thread.startVirtualThread(task);
      try
      {
         return (task.get(remaining, TimeUnit.NANOSECONDS));
      }
      catch(TimeoutException e)
      {
         return (HealthCheckOutcome.down("CDN connection test timed out"));
      }
      catch(InterruptedException e)
      {
         Thread.currentThread().interrupt();
         return (HealthCheckOutcome.down("CDN connection test interrupted"));
      }
      catch(ExecutionException e)
      {
         return (HealthCheckOutcome.down("CDN connection test failed"));
      }
      finally
      {
         task.cancel(true);
      }
   }
}
