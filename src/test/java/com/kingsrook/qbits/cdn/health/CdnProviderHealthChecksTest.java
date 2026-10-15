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


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.spi.CdnOperations;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.health.HealthStatus;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;


/*******************************************************************************
 ** Provider checks reuse the probe, honor deadlines and expose only safe facts.
 *******************************************************************************/
class CdnProviderHealthChecksTest
{
   private static final String SECRET = "S3NT1NEL-secret";
   private QInstance instance;
   private Integer providerId;
   private CdnTestSupport.FakePathProvider adapter;



   /*******************************************************************************
    ** A memory-backed instance and the existing fake adapter, with no network.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      instance = CdnTestSupport.setUp();
      providerId = CdnTestSupport.liveProvider(CdnProviderType.CLOUDFRONT, null);
      adapter = spy(new CdnTestSupport.FakePathProvider());
      CdnRuntime.registerProvider(CdnProviderType.CLOUDFRONT, () -> adapter);
   }



   /*******************************************************************************
    ** Restore process-wide test state.
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      CdnRuntime.setEnvironment(null);
      CdnRuntime.setClock(null);
      Thread.interrupted();
      QContext.clear();
   }



   /*******************************************************************************
    ** The producer registers a dynamic dependency supplier with the spec defaults.
    *******************************************************************************/
   @Test
   void registrationAndRefresh() throws Exception
   {
      var registry = HealthChecksMetaData.ofOrWithNew(instance);
      assertThat(registry.getSuppliers()).singleElement().satisfies(supplier ->
      {
         assertThat(supplier.getNamePrefix()).isEqualTo("cdn.provider");
         assertThat(supplier.getComponent()).isEqualTo("cdn");
      });
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "disabled").withValue("providerType", "CLOUDFRONT").withValue("enabled", false));
      // Match the runner's automated context rather than relying on CDN UI permissions.
      QContext.init(instance, new QSession().withUser(new QUser().withIdReference("health_runner").withFullName("health_runner"))
         .withSecurityKeyValues(Map.of()).withPermissions("health.read", "health.write"));
      assertThat(registry.resolveAll(instance)).singleElement().satisfies(check ->
      {
         assertThat(check.getName()).isEqualTo("cdn.provider." + providerId).matches("[a-z0-9]+([._-][a-z0-9]+)*");
         assertThat(check.getComponent()).isEqualTo("cdn");
         assertThat(check.getKind()).isEqualTo(HealthCheckKind.DEPENDENCY);
         assertThat(check.getCategory()).isEqualTo(HealthCategory.CDN);
         assertThat(check.getIntervalSeconds()).isEqualTo(900);
         assertThat(check.getTimeoutMs()).isEqualTo(20000);
         assertThat(check.getIncludeInReadiness()).isFalse();
      });
      assertThat(check().getCheckInstance().run(input()).getStatus()).isEqualTo(HealthStatus.UP);
      update(new QRecord().withValue("enabled", false));
      assertThat(registry.resolveAll(instance)).isEmpty();
      update(new QRecord().withValue("enabled", true));
      assertThat(registry.resolveAll(instance)).hasSize(1);
   }



   /*******************************************************************************
    ** Map success, missing origin protection and failure without writing lastHealth.
    *******************************************************************************/
   @Test
   void connectionStates() throws Exception
   {
      var check = check().getCheckInstance();
      HealthCheckOutcome up = check.run(input());
      assertThat(up.getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(up.getDetails().getValues()).containsExactlyInAnyOrderEntriesOf(Map.of(
         "providerType", "CLOUDFRONT", "status", "Deployed", "originHeaderConfigured", true));
      doReturn(new CdnOperations.ConnectionTest(true, null, null, null, false, "InProgress", "unused")).when(adapter).connectionTest(any());
      HealthCheckOutcome degraded = check.run(input());
      assertThat(degraded.getStatus()).isEqualTo(HealthStatus.DEGRADED);
      assertThat(degraded.getDetails().getValues()).containsEntry("originHeaderConfigured", false).containsEntry("status", "InProgress");
      doReturn(new CdnOperations.ConnectionTest(false, null, null, null, false, null, "unused")).when(adapter).connectionTest(any());
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.DOWN);
      QRecord row = new GetAction().executeForRecord(new GetInput(CdnTables.PROVIDER).withPrimaryKey(providerId));
      assertThat(row.getValue("lastHealthCheckDate")).isNull();
      assertThat(row.getValue("lastHealthStatus")).isNull();
   }



   /*******************************************************************************
    ** A previously resolved check reloads config and skips disabled/deleted rows.
    *******************************************************************************/
   @Test
   void reloadsProviderAndHandlesUnsupported() throws Exception
   {
      var check = check().getCheckInstance();
      update(new QRecord().withValue("providerType", "NONE"));
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      update(new QRecord().withValue("enabled", false));
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      new DeleteAction().execute(new DeleteInput(CdnTables.PROVIDER).withPrimaryKeys(List.of(providerId)));
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      verify(adapter, never()).connectionTest(any());
   }



   /*******************************************************************************
    ** Poison credentials, every free-form probe field and thrown error messages.
    ** HealthRunner.sanitize is package-private, so assert the raw boundary here.
    *******************************************************************************/
   @Test
   void noSecretsInOutcomes() throws Exception
   {
      update(new QRecord().withValue("secretRef", "TEST_CDN").withValue("name", SECRET)
         .withValue("zoneOrDistributionId", SECRET).withValue("settingsJson", "{\"originVerifyHeader\":\"" + SECRET + "\"}"));
      CdnRuntime.setEnvironment(name -> SECRET);
      for(HealthStatus expected : List.of(HealthStatus.UP, HealthStatus.DEGRADED, HealthStatus.DOWN))
      {
         doAnswer(invocation ->
         {
            CdnProviderConfig config = invocation.getArgument(0);
            assertThat(CdnRuntime.environment(config.secretRef() + "_ACCESS_KEY_ID")).isEqualTo(SECRET);
            assertThat(CdnRuntime.environment(config.secretRef() + "_SECRET_ACCESS_KEY")).isEqualTo(SECRET);
            return (new CdnOperations.ConnectionTest(expected != HealthStatus.DOWN, SECRET, SECRET, 443,
               expected == HealthStatus.UP, SECRET, SECRET));
         }).when(adapter).connectionTest(any());
         HealthCheckOutcome outcome = check().getCheckInstance().run(input());
         assertThat(outcome.getStatus()).isEqualTo(expected);
         assertClean(outcome);
      }
      doThrow(new IllegalStateException(SECRET)).when(adapter).connectionTest(any());
      HealthCheckOutcome failed = check().getCheckInstance().run(input());
      assertThat(failed.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertClean(failed);
      update(new QRecord().withValue("providerType", "NONE"));
      assertClean(check().getCheckInstance().run(input()));
   }



   /*******************************************************************************
    ** An expired deadline never starts the existing probe.
    *******************************************************************************/
   @Test
   void expiredDeadline() throws Exception
   {
      HealthCheckOutcome outcome = check().getCheckInstance().run(input().withDeadline(Instant.now().minusSeconds(1)));
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(outcome.getMessage()).contains("timed out");
      verify(adapter, never()).connectionTest(any());
   }



   /*******************************************************************************
    ** A slow adapter is cancelled at the deadline, without a blocking executor close.
    *******************************************************************************/
   @Test
   void deadlineCancelsProbe() throws Exception
   {
      CountDownLatch cancelled = new CountDownLatch(1);
      doAnswer(invocation ->
      {
         try
         {
            new CountDownLatch(1).await();
            return (null);
         }
         finally
         {
            cancelled.countDown();
         }
      }).when(adapter).connectionTest(any());
      HealthCheckOutcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
      {
         QContext.init(instance, new QSession());
         try
         {
            return (check().getCheckInstance().run(input().withDeadline(Instant.now().plusMillis(200))));
         }
         finally
         {
            QContext.clear();
         }
      });
      assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(outcome.getMessage()).contains("timed out");
      assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
   }



   /*******************************************************************************
    ** Runner cancellation preserves the caller's interrupt status.
    *******************************************************************************/
   @Test
   void interruptedProbe() throws Exception
   {
      doAnswer(invocation ->
      {
         new CountDownLatch(1).await();
         return (null);
      }).when(adapter).connectionTest(any());
      var check = check().getCheckInstance();
      Thread.currentThread().interrupt();
      try
      {
         HealthCheckOutcome outcome = check.run(input());
         assertThat(outcome.getStatus()).isEqualTo(HealthStatus.DOWN);
         assertThat(outcome.getMessage()).contains("interrupted");
         assertThat(Thread.currentThread().isInterrupted()).isTrue();
      }
      finally
      {
         Thread.interrupted();
      }
   }



   /*******************************************************************************
    ** Resolve through the actual registration, including QCodeReference loading.
    *******************************************************************************/
   private HealthCheckMetaData check() throws Exception
   {
      return (HealthChecksMetaData.ofOrWithNew(instance).resolveAll(instance).getFirst());
   }



   /*******************************************************************************
    ** Fresh deadline for each call.
    *******************************************************************************/
   private HealthCheckInput input()
   {
      return (new HealthCheckInput().withQInstance(instance).withDeadline(Instant.now().plusSeconds(5)));
   }



   /*******************************************************************************
    ** Modify the real memory row to exercise fresh reads.
    *******************************************************************************/
   private void update(QRecord values) throws Exception
   {
      new UpdateAction().execute(new UpdateInput(CdnTables.PROVIDER).withRecord(values.withValue("id", providerId)));
   }



   /*******************************************************************************
    ** No provider-controlled text can reach the central health boundary.
    *******************************************************************************/
   private static void assertClean(HealthCheckOutcome outcome)
   {
      assertThat(outcome.getMessage()).doesNotContain(SECRET, "\n", "\r");
      assertThat(outcome.getDetails().toJson()).doesNotContain(SECRET, "TEST_CDN", "origin.example.com", "d1.cloudfront.net");
   }
}
