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

package com.kingsrook.qbits.cdn.planning;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Unit test for PathInvalidationPlanner.
 *******************************************************************************/
class PathInvalidationPlannerTest
{
   private final PathInvalidationPlanner planner = new PathInvalidationPlanner(PathPlannerSettings.CLOUDFRONT_DEFAULTS);



   /*******************************************************************************
    ** A typical publish: the post page and its API record, hubs skipped.
    *******************************************************************************/
   @Test
   void typicalPublishCostsTwoPaths()
   {
      PathPlan plan = planner.plan(List.of(
         InvalidationPath.leaf("/blog/quick-bits/my-post*"),
         InvalidationPath.leaf("/v1/blog-posts/123*"),
         InvalidationPath.hub("/blog/quick-bits"),
         InvalidationPath.hub("/")), false, 0);

      assertThat(plan.band()).isEqualTo(PathPlan.Band.NORMAL);
      assertThat(plan.allPaths()).containsExactly("/blog/quick-bits/my-post*", "/v1/blog-posts/123*");
      assertThat(plan.billablePaths()).isEqualTo(2);
      assertThat(plan.skippedHubs()).isEqualTo(2);
      assertThat(plan.batches()).hasSize(1);
   }



   /*******************************************************************************
    ** Only hubs: nothing to send; a manual purge can include them.
    *******************************************************************************/
   @Test
   void hubsOnly()
   {
      List<InvalidationPath> hubs = List.of(InvalidationPath.hub("/"), InvalidationPath.hub("/blog/quick-bits"));
      assertThat(planner.plan(hubs, false, 0).band()).isEqualTo(PathPlan.Band.NOTHING);
      assertThat(planner.plan(hubs, true, 0).allPaths()).containsExactly("/", "/blog/quick-bits");
      assertThat(planner.plan(List.of(), false, 0).band()).isEqualTo(PathPlan.Band.NOTHING);
   }



   /*******************************************************************************
    ** Old and new slug both purge; duplicates and covered paths drop out.
    *******************************************************************************/
   @Test
   void coveredAndDuplicatePathsDropOut()
   {
      PathPlan plan = planner.plan(List.of(
         InvalidationPath.leaf("/blog/quick-bits/old-slug*"),
         InvalidationPath.leaf("/blog/quick-bits/new-slug*"),
         InvalidationPath.leaf("/blog/quick-bits/new-slug*"),
         InvalidationPath.leaf("/v1/blog-posts/1"),
         InvalidationPath.leaf("/v1/blog-posts/1*"),
         InvalidationPath.leaf("/v1/*"),
         InvalidationPath.leaf("/v1/blogs/7*")), false, 0);

      assertThat(plan.allPaths()).containsExactly("/blog/quick-bits/old-slug*", "/blog/quick-bits/new-slug*", "/v1/*");
   }



   /*******************************************************************************
    ** More than five siblings collapse to one wildcard.
    *******************************************************************************/
   @Test
   void siblingsCollapse()
   {
      List<InvalidationPath> many = new ArrayList<>();
      for(int i = 0; i < 6; i++)
      {
         many.add(InvalidationPath.leaf("/photography/iceland/photo-" + i + "*"));
      }
      many.add(InvalidationPath.leaf("/about*"));
      PathPlan plan = planner.plan(many, false, 0);
      assertThat(plan.allPaths()).containsExactly("/photography/iceland/*", "/about*");
      assertThat(plan.billablePaths()).isEqualTo(2);

      List<InvalidationPath> five = many.subList(0, 5);
      assertThat(planner.plan(five, false, 0).billablePaths()).isEqualTo(5);
   }



   /*******************************************************************************
    ** Above the soft limit the plan is coarsened to one wildcard per section.
    *******************************************************************************/
   @Test
   void coarsensAboveSoftLimit()
   {
      PathPlan plan = planner.plan(List.of(
         InvalidationPath.leaf("/blog/quick-bits/my-post*"),
         InvalidationPath.leaf("/blog/other/post*"),
         InvalidationPath.leaf("/v1/blog-posts/123*"),
         InvalidationPath.leaf("/about*")), false, 799);

      assertThat(plan.band()).isEqualTo(PathPlan.Band.COARSENED);
      assertThat(plan.allPaths()).containsExactly("/blog/*", "/v1/*", "/about*");
      assertThat(plan.billablePaths()).isEqualTo(3);
   }



   /*******************************************************************************
    ** Above the hard limit: paid within the cap, deferred beyond it or in
    ** DEFER mode.
    *******************************************************************************/
   @Test
   void overBudget()
   {
      List<InvalidationPath> two = List.of(InvalidationPath.leaf("/blog/a/b*"), InvalidationPath.leaf("/v1/blog-posts/1*"));
      PathPlan paid = planner.plan(two, false, 1000);
      assertThat(paid.band()).isEqualTo(PathPlan.Band.PAID);
      assertThat(paid.billablePaths()).isEqualTo(2);

      PathPlan capped = planner.plan(two, false, 1999);
      assertThat(capped.band()).isEqualTo(PathPlan.Band.DEFERRED);
      assertThat(capped.batches()).isEmpty();

      PathInvalidationPlanner deferring = new PathInvalidationPlanner(PathPlannerSettings.from(
         new CdnProviderConfig("cf", CdnProviderType.CLOUDFRONT, "E123", "CDN_AWS", Map.of("overBudgetMode", "defer"))));
      assertThat(deferring.plan(two, false, 1000).band()).isEqualTo(PathPlan.Band.DEFERRED);
   }



   /*******************************************************************************
    ** Batches respect the wildcard and path limits.
    *******************************************************************************/
   @Test
   void batchesRespectLimits()
   {
      PathInvalidationPlanner small = new PathInvalidationPlanner(new PathPlannerSettings(1000, 800, 1000, 1000,
         PathPlannerSettings.OverBudgetMode.PAY, 100, 3, 2));
      List<InvalidationPath> paths = List.of(
         InvalidationPath.leaf("/a/1*"), InvalidationPath.leaf("/b/1*"), InvalidationPath.leaf("/c/1*"),
         InvalidationPath.leaf("/d/1"), InvalidationPath.leaf("/e/1"), InvalidationPath.leaf("/f/1"), InvalidationPath.leaf("/g/1"));
      PathPlan plan = small.plan(paths, false, 0);
      assertThat(plan.batches()).containsExactly(
         List.of("/a/1*", "/b/1*"),
         List.of("/c/1*", "/d/1", "/e/1"),
         List.of("/f/1", "/g/1"));
      assertThat(plan.billablePaths()).isEqualTo(7);
   }



   /*******************************************************************************
    ** Settings come from a provider row, with defaults for missing or bad values.
    *******************************************************************************/
   @Test
   void settingsFromConfig()
   {
      PathPlannerSettings settings = PathPlannerSettings.from(new CdnProviderConfig("cf", CdnProviderType.CLOUDFRONT, "E1", "X",
         Map.of("softPathLimit", " 500 ", "hardPathLimit", "oops")));
      assertThat(settings.softPathLimit()).isEqualTo(500);
      assertThat(settings.hardPathLimit()).isEqualTo(1000);
      assertThat(settings.overBudgetMode()).isEqualTo(PathPlannerSettings.OverBudgetMode.PAY);
   }



   /*******************************************************************************
    ** Paths are validated.
    *******************************************************************************/
   @Test
   void invalidPaths()
   {
      assertThatThrownBy(() -> InvalidationPath.leaf("blog/x")).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> InvalidationPath.leaf("/blog/*/x")).isInstanceOf(IllegalArgumentException.class);
      assertThat(PathInvalidationPlanner.parent("/about")).isEmpty();
      assertThat(PathInvalidationPlanner.parent("/blog/x/y*")).isEqualTo("/blog/x");
   }
}
