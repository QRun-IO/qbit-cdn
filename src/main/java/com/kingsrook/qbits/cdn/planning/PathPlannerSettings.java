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


import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;


/*******************************************************************************
 ** Knobs for PathInvalidationPlanner; defaults follow the design spec
 ** (section 4.3) for CloudFront's 1,000 free paths per month.
 **
 ** @param freePathsPerMonth    paths the provider bills at no cost each month
 ** @param softPathLimit        above this month-to-date count, coarsen
 ** @param hardPathLimit        above this, the over-budget mode applies
 ** @param monthlyPaidPathCap   most paid paths per month when paying
 ** @param overBudgetMode       PAY or DEFER above the hard limit
 ** @param collapseThreshold    more siblings than this collapse to parent/*
 ** @param maxPathsPerBatch     most paths in one provider call
 ** @param maxWildcardsPerBatch most wildcard paths in one provider call
 *******************************************************************************/
public record PathPlannerSettings(int freePathsPerMonth, int softPathLimit, int hardPathLimit, int monthlyPaidPathCap,
                                  OverBudgetMode overBudgetMode, int collapseThreshold, int maxPathsPerBatch, int maxWildcardsPerBatch)
{
   /** CloudFront defaults. */
   public static final PathPlannerSettings CLOUDFRONT_DEFAULTS = new PathPlannerSettings(1000, 800, 1000, 1000, OverBudgetMode.PAY, 5, 3000, 10);



   /*******************************************************************************
    ** What to do above the hard limit.
    *******************************************************************************/
   public enum OverBudgetMode
   {
      PAY,
      DEFER
   }



   /*******************************************************************************
    ** Defaults overridden by a provider row's settings.
    *******************************************************************************/
   public static PathPlannerSettings from(CdnProviderConfig config)
   {
      PathPlannerSettings d = CLOUDFRONT_DEFAULTS;
      OverBudgetMode mode = "DEFER".equalsIgnoreCase(config.settings().get("overBudgetMode")) ? OverBudgetMode.DEFER : d.overBudgetMode();
      return new PathPlannerSettings(
         config.intSetting("freePathsPerMonth", d.freePathsPerMonth()),
         config.intSetting("softPathLimit", d.softPathLimit()),
         config.intSetting("hardPathLimit", d.hardPathLimit()),
         config.intSetting("monthlyPaidPathCap", d.monthlyPaidPathCap()),
         mode,
         config.intSetting("collapseThreshold", d.collapseThreshold()),
         config.intSetting("maxPathsPerBatch", d.maxPathsPerBatch()),
         config.intSetting("maxWildcardsPerBatch", d.maxWildcardsPerBatch()));
   }
}
