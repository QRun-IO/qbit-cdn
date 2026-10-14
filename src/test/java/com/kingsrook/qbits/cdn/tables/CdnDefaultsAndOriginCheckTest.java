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

package com.kingsrook.qbits.cdn.tables;


import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.app.CdnSetupStatus;
import com.kingsrook.qbits.cdn.checks.CdnOriginCheck;
import com.kingsrook.qbits.cdn.headers.CdnResponseHeaderPolicy;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.processes.CdnDefaultsProcesses;
import com.kingsrook.qbits.cdn.processes.CdnOriginCheckProcesses;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The "Create default ..." table actions (insert-only, change nothing that is
 ** there) and the origin header check (the writer of cdn_origin_check, which had
 ** none): what it counts, what it records, and the action and nightly entry points.
 *******************************************************************************/
class CdnDefaultsAndOriginCheckTest
{
   private final Map<String, CdnOriginCheck.Response> origin = new LinkedHashMap<>();
   private final List<String>                         asked  = new java.util.ArrayList<>();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      CdnTestSupport.setUp();
      CdnOriginCheck.setFetcher((host, path) ->
      {
         asked.add(host + path);
         CdnOriginCheck.Response response = origin.get(path);
         if(response == null)
         {
            throw (new IOException("connection refused"));
         }
         return (response);
      });
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      CdnOriginCheck.setFetcher(null);
      CdnResponseHeaderPolicy.clearCache();
   }



   /*******************************************************************************
    ** Run a table action through Review and then Result; returns the result lines.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private static List<String> run(String process, String recordIds) throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(process);
      if(recordIds != null)
      {
         input.addValue("recordsParam", "recordIds");
         input.addValue("recordIds", recordIds);
      }
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.BREAK);
      RunProcessOutput review = new RunProcessAction().execute(input);
      RunProcessInput go = new RunProcessInput();
      go.setProcessName(process);
      go.setProcessUUID(review.getProcessUUID());
      go.setStartAfterStep(StreamedETLWithFrontendProcess.STEP_NAME_REVIEW);
      go.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput result = new RunProcessAction().execute(go);
      List<ProcessSummaryLineInterface> lines = (List<ProcessSummaryLineInterface>) result.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      return (lines == null ? List.of() : lines.stream().map(line ->
      {
         ProcessSummaryLine summary = (ProcessSummaryLine) line;
         summary.pickMessage(true);
         return (summary.getCount() + " " + summary.getMessage());
      }).toList());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static List<QRecord> rows(String table) throws Exception
   {
      return (new QueryAction().execute(new QueryInput(table)).getRecords());
   }



   /*******************************************************************************
    ** Create default provider adds No CDN once; and with it a record change is
    ** recorded as a would-be purge (with no provider it is not recorded at all).
    *******************************************************************************/
   @Test
   void defaultProviderMakesPurgesRecorded() throws Exception
   {
      CdnTestSupport.postRule();
      CdnTestSupport.insert("post", new QRecord().withValue("slug", "a").withValue("title", "T").withValue("blogId", 1).withValue("isPublished", true));
      assertThat(rows(CdnTables.PURGE_REQUEST)).as("no provider: nothing is recorded").isEmpty();

      assertThat(run(CdnDefaultsProcesses.CREATE_PROVIDER, null)).containsExactly("1 default row was added");
      assertThat(rows(CdnTables.PROVIDER)).singleElement().satisfies(p ->
      {
         assertThat(p.getValueString("name")).isEqualTo(CdnDefaultsProcesses.NONE_PROVIDER_NAME);
         assertThat(p.getValueString("providerType")).isEqualTo("NONE");
         assertThat(p.getValueBoolean("enabled")).isTrue();
      });

      CdnTestSupport.insert("post", new QRecord().withValue("slug", "b").withValue("title", "T").withValue("blogId", 1).withValue("isPublished", true));
      assertThat(rows(CdnTables.PURGE_REQUEST)).singleElement().satisfies(r -> assertThat(r.getValueString("status")).isEqualTo("SKIPPED_NONE_PROVIDER"));

      ///////////////////////////////////////////////////////////////////////
      // insert-only: again changes nothing, and an edited row is left alone //
      ///////////////////////////////////////////////////////////////////////
      new UpdateAction().execute(new UpdateInput(CdnTables.PROVIDER).withRecord(new QRecord().withValue("id", rows(CdnTables.PROVIDER).get(0).getValue("id")).withValue("name", "Ours")));
      assertThat(run(CdnDefaultsProcesses.CREATE_PROVIDER, null)).containsExactly("null Nothing to add: the defaults are already there.");
      assertThat(rows(CdnTables.PROVIDER)).singleElement().satisfies(p -> assertThat(p.getValueString("name")).isEqualTo("Ours"));
   }



   /*******************************************************************************
    ** Create default policies adds two turned-off policies (so nothing changes until
    ** one is turned on), by name; an edited policy survives a second run; turning one
    ** on makes the header decision change.
    *******************************************************************************/
   @Test
   void defaultPoliciesAreOffUntilTurnedOn() throws Exception
   {
      assertThat(run(CdnDefaultsProcesses.CREATE_POLICIES, null)).containsExactly("2 default rows were added");
      List<QRecord> policies = rows(CdnTables.CACHE_POLICY);
      assertThat(policies).hasSize(2).allSatisfy(p -> assertThat(p.getValueBoolean("enabled")).isFalse());
      CdnResponseHeaderPolicy.clearCache();
      assertThat(CdnResponseHeaderPolicy.current()).as("turned-off policies change no response").isEmpty();

      QRecord assets = policies.stream().filter(p -> CdnDefaultsProcesses.ASSETS_POLICY_NAME.equals(p.getValueString("name"))).findFirst().orElseThrow();
      new UpdateAction().execute(new UpdateInput(CdnTables.CACHE_POLICY).withRecord(new QRecord().withValue("id", assets.getValue("id")).withValue("enabled", true).withValue("browserMaxAge", 600)));
      CdnResponseHeaderPolicy.clearCache();
      CdnResponseHeaderPolicy.Decision decision = CdnResponseHeaderPolicy.decide(new CdnResponseHeaderPolicy.Request("www.example.com", "/assets/app.js", null, null, false, false, List.of(), List.of()), null);
      assertThat(decision.policyName()).isEqualTo(CdnDefaultsProcesses.ASSETS_POLICY_NAME);
      assertThat(decision.cacheControl()).startsWith("public, max-age=600").endsWith("immutable");

      assertThat(run(CdnDefaultsProcesses.CREATE_POLICIES, null)).containsExactly("null Nothing to add: the defaults are already there.");
      assertThat(rows(CdnTables.CACHE_POLICY)).hasSize(2).anySatisfy(p -> assertThat(p.getValueInteger("browserMaxAge")).isEqualTo(600));
   }



   /*******************************************************************************
    ** Create default rules adds a tags-only rule for each purgeable table without
    ** one, and the rule makes a change record tags.
    *******************************************************************************/
   @Test
   void defaultRulesCoverPurgeableTables() throws Exception
   {
      assertThat(run(CdnDefaultsProcesses.CREATE_RULES, null)).containsExactly("1 default row was added");
      assertThat(rows(CdnTables.TABLE_TAG_RULE)).singleElement().satisfies(r ->
      {
         assertThat(r.getValueString("tableName")).isEqualTo("post");
         assertThat(r.getValueString("tagTemplates")).isEqualTo("post:{id}\npost");
         assertThat(r.getValueBoolean("enabled")).isTrue();
      });
      CdnTestSupport.insert(CdnTables.PROVIDER, new QRecord().withValue("name", "none").withValue("providerType", "NONE").withValue("enabled", true));
      QRecord post = CdnTestSupport.insert("post", new QRecord().withValue("slug", "a").withValue("title", "T").withValue("blogId", 1).withValue("isPublished", true)).get(0);
      assertThat(rows(CdnTables.PURGE_REQUEST)).singleElement().satisfies(r -> assertThat(r.getValueString("tags")).contains("post:" + post.getValue("id")));

      assertThat(run(CdnDefaultsProcesses.CREATE_RULES, null)).containsExactly("null Nothing to add: the defaults are already there.");
      assertThat(rows(CdnTables.TABLE_TAG_RULE)).hasSize(1);
   }



   /*******************************************************************************
    ** The origin check: a good page, a page with no Cache-Control, a page whose
    ** Cache-Control differs from its policy, a 404 and an unreachable page.
    *******************************************************************************/
   @Test
   void originCheckCountsRightWrongAndUnreadable() throws Exception
   {
      Integer provider = CdnTestSupport.liveProvider(com.kingsrook.qbits.cdn.spi.CdnProviderType.CLOUDFRONT, null);
      new UpdateAction().execute(new UpdateInput(CdnTables.PROVIDER).withRecord(new QRecord().withValue("id", provider).withValue("originHost", "origin.example.com:443")));
      CdnTestSupport.insert(CdnTables.CACHE_POLICY,
         new QRecord().withValue("name", "blog").withValue("matchType", "PREFIX").withValue("matchValue", "/blog").withValue("policyClass", "LEAF").withValue("browserMaxAge", 60).withValue("enabled", true),
         new QRecord().withValue("name", "news").withValue("matchType", "PREFIX").withValue("matchValue", "/news").withValue("policyClass", "LEAF").withValue("browserMaxAge", 60).withValue("enabled", true));
      CdnTestSupport.insert(CdnTables.TABLE_TAG_RULE, new QRecord().withValue("tableName", "post").withValue("enabled", true)
         .withValue("invalidationPaths", "/about\n/blog/{slug}*\nhub:/blog"));
      CdnResponseHeaderPolicy.clearCache();

      origin.put("/", new CdnOriginCheck.Response(200, Map.of("cache-control", "max-age=5")));
      origin.put("/blog", new CdnOriginCheck.Response(200, Map.of("cache-control", "Public, max-age=60")));
      origin.put("/news", new CdnOriginCheck.Response(200, Map.of("cache-control", "public, max-age=999")));
      origin.put("/about", new CdnOriginCheck.Response(200, Map.of()));
      CdnTestSupport.insert(CdnTables.CACHE_POLICY, new QRecord().withValue("name", "gone").withValue("matchType", "PREFIX").withValue("matchValue", "/gone").withValue("policyClass", "LEAF").withValue("enabled", true),
         new QRecord().withValue("name", "down").withValue("matchType", "PREFIX").withValue("matchValue", "/down").withValue("policyClass", "LEAF").withValue("enabled", true));
      CdnResponseHeaderPolicy.clearCache();
      origin.put("/gone", new CdnOriginCheck.Response(404, Map.of()));

      QRecord hostname = rows(CdnTables.HOSTNAME).get(0);
      CdnOriginCheck.Result result = CdnOriginCheck.checkAndRecord(hostname);

      assertThat(asked).as("the provider's origin host (without a port) is asked, not the public name").allMatch(url -> url.startsWith("origin.example.com/"));
      assertThat(result.checked()).isEqualTo(6);
      assertThat(result.correct()).as("/ (any Cache-Control, no policy) and /blog (equals its policy, ignoring case)").isEqualTo(2);
      assertThat(result.wrong()).as("/about has no header; /news differs from its policy").isEqualTo(2);
      assertThat(result.unreadable()).as("/gone answered 404; /down is unreachable").isEqualTo(2);
      assertThat(result.problems()).anyMatch(p -> p.startsWith("/about: no Cache-Control")).anyMatch(p -> p.startsWith("/news") && p.contains("public, max-age=60"))
         .anyMatch(p -> p.startsWith("/gone: the origin answered 404")).anyMatch(p -> p.startsWith("/down: could not be read"));

      assertThat(rows(CdnTables.ORIGIN_CHECK)).singleElement().satisfies(row ->
      {
         assertThat(row.getValueString("scope")).isEqualTo("www.example.com");
         assertThat(row.getValueInteger("objectsChecked")).isEqualTo(6);
         assertThat(row.getValueInteger("correct")).isEqualTo(2);
         assertThat(row.getValueInteger("missingOrWrong")).isEqualTo(2);
         assertThat(row.getValueInteger("unreadable")).isEqualTo(2);
         assertThat(row.getValue("checkDate")).isNotNull();
      });
   }



   /*******************************************************************************
    ** Sample paths are bounded and only literal paths are used.
    *******************************************************************************/
   @Test
   void samplePathsAreLiteralAndBounded() throws Exception
   {
      StringBuilder paths = new StringBuilder("/blog/{slug}*");
      for(int i = 0; i < 40; i++)
      {
         paths.append("\n/page-").append(i);
      }
      CdnTestSupport.insert(CdnTables.TABLE_TAG_RULE, new QRecord().withValue("tableName", "post").withValue("enabled", true).withValue("invalidationPaths", paths.toString()));
      List<String> sample = CdnOriginCheck.samplePaths("www.example.com");
      assertThat(sample).hasSize(CdnOriginCheck.MAX_PATHS).startsWith("/", "/page-0").noneMatch(p -> p.contains("{") || p.contains("*"));
   }



   /*******************************************************************************
    ** The record action writes a row per selected hostname; the nightly entry point
    ** skips hostnames that are Off.
    *******************************************************************************/
   @Test
   void actionAndNightlyRun() throws Exception
   {
      CdnTestSupport.liveProvider(com.kingsrook.qbits.cdn.spi.CdnProviderType.CLOUDFRONT, null);
      CdnTestSupport.insert(CdnTables.HOSTNAME, new QRecord().withValue("hostname", "off.example.com").withValue("cdnProviderId", 1).withValue("stage", "OFF"));
      origin.put("/", new CdnOriginCheck.Response(200, Map.of("cache-control", "max-age=5")));

      List<String> lines = run(CdnOriginCheckProcesses.CHECK_ORIGIN, "1");
      assertThat(lines).containsExactly("1 hostname has the right headers");
      assertThat(rows(CdnTables.ORIGIN_CHECK)).hasSize(1);

      new CdnOriginCheckProcesses.ScheduledStep().run(new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput(), new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput());
      assertThat(rows(CdnTables.ORIGIN_CHECK)).extracting(r -> r.getValueString("scope")).containsExactly("www.example.com", "www.example.com");

      CdnRuntime.setClock(null);
      assertThat(CdnSetupStatus.load()).anySatisfy(item ->
      {
         assertThat(item.tableName()).isEqualTo(CdnTables.ORIGIN_CHECK);
         assertThat(item.state()).isEqualTo(CdnSetupStatus.State.OK);
      });
   }
}
