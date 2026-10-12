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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/*******************************************************************************
 ** Plans path invalidations for providers without tag purge (CloudFront,
 ** Azure Front Door) within a monthly path budget (design spec section 4.3):
 **
 ** 1. Hubs are dropped (they expire by a short TTL) unless asked for.
 ** 2. Paths a wildcard in the same plan already covers are removed.
 ** 3. More than collapseThreshold siblings under one parent become parent/*.
 ** 4. Budget: within the soft limit the plan is sent as is; above it, it is
 **    coarsened to one wildcard per top-level section; above the hard limit
 **    it is paid (up to the monthly cap) or deferred to the TTL.
 ** 5. The result is split into batches by path and wildcard count.
 **
 ** A wildcard ("/a/b*") is billed as one path and also covers query-string
 ** variants such as Next.js RSC requests ("?_rsc=").
 *******************************************************************************/
public final class PathInvalidationPlanner
{
   private final PathPlannerSettings settings;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public PathInvalidationPlanner(PathPlannerSettings settings)
   {
      this.settings = settings;
   }



   /*******************************************************************************
    ** Plan one coalesced group.
    **
    ** @param candidates      rendered candidate paths
    ** @param includeHubs     true for a manual purge that wants hubs too
    ** @param billedThisMonth paths already billed this month (UTC)
    *******************************************************************************/
   public PathPlan plan(Collection<InvalidationPath> candidates, boolean includeHubs, int billedThisMonth)
   {
      Set<String> paths = new LinkedHashSet<>();
      int skippedHubs = 0;
      for(InvalidationPath candidate : candidates)
      {
         if(candidate.hub() && !includeHubs)
         {
            skippedHubs++;
         }
         else
         {
            paths.add(candidate.path());
         }
      }
      if(paths.isEmpty())
      {
         return new PathPlan(PathPlan.Band.NOTHING, List.of(), 0, skippedHubs);
      }

      List<String> planned = removeCovered(collapse(removeCovered(new ArrayList<>(paths))));
      PathPlan.Band band = PathPlan.Band.NORMAL;
      if(billedThisMonth + planned.size() > settings.softPathLimit())
      {
         planned = removeCovered(coarsen(planned));
         band = PathPlan.Band.COARSENED;
      }
      if(billedThisMonth + planned.size() > settings.hardPathLimit())
      {
         int paidAfter = billedThisMonth + planned.size() - settings.freePathsPerMonth();
         if(settings.overBudgetMode() != PathPlannerSettings.OverBudgetMode.PAY || paidAfter > settings.monthlyPaidPathCap())
         {
            return new PathPlan(PathPlan.Band.DEFERRED, List.of(), 0, skippedHubs);
         }
         band = PathPlan.Band.PAID;
      }
      return new PathPlan(band, batches(planned), planned.size(), skippedHubs);
   }



   /*******************************************************************************
    ** Drop every path that a broader wildcard in the list covers.
    *******************************************************************************/
   static List<String> removeCovered(List<String> paths)
   {
      List<String> result = new ArrayList<>();
      for(String path : paths)
      {
         if(!result.contains(path) && paths.stream().noneMatch(other -> covers(other, path)))
         {
            result.add(path);
         }
      }
      return result;
   }



   /*******************************************************************************
    ** True when wildcard "other" covers a different, narrower "path".
    *******************************************************************************/
   static boolean covers(String other, String path)
   {
      if(other.equals(path) || !other.endsWith("*"))
      {
         return false;
      }
      String prefix = other.substring(0, other.length() - 1);
      String bare = path.endsWith("*") ? path.substring(0, path.length() - 1) : path;
      return bare.startsWith(prefix) && bare.length() >= prefix.length();
   }



   /*******************************************************************************
    ** Replace more than collapseThreshold siblings with parent/*.
    *******************************************************************************/
   List<String> collapse(List<String> paths)
   {
      Map<String, List<String>> byParent = new LinkedHashMap<>();
      for(String path : paths)
      {
         byParent.computeIfAbsent(parent(path), k -> new ArrayList<>()).add(path);
      }
      List<String> result = new ArrayList<>();
      for(Map.Entry<String, List<String>> entry : byParent.entrySet())
      {
         if(entry.getValue().size() > settings.collapseThreshold())
         {
            result.add(entry.getKey() + "/*");
         }
         else
         {
            result.addAll(entry.getValue());
         }
      }
      return result;
   }



   /*******************************************************************************
    ** One wildcard per top-level section ("/blog/x/y*" becomes "/blog/*"); a
    ** top-level path such as "/about*" stays as it is.
    *******************************************************************************/
   static List<String> coarsen(List<String> paths)
   {
      Set<String> result = new LinkedHashSet<>();
      for(String path : paths)
      {
         String bare = path.endsWith("*") ? path.substring(0, path.length() - 1) : path;
         int second = bare.indexOf('/', 1);
         result.add(second < 0 ? path : bare.substring(0, second) + "/*");
      }
      return new ArrayList<>(result);
   }



   /*******************************************************************************
    ** The parent of a path ("/blog/x/y*" is "/blog/x"; "/about" is "").
    *******************************************************************************/
   static String parent(String path)
   {
      String bare = path.endsWith("*") ? path.substring(0, path.length() - 1) : path;
      int slash = bare.lastIndexOf('/');
      return slash <= 0 ? "" : bare.substring(0, slash);
   }



   /*******************************************************************************
    ** Split into batches within the path and wildcard limits.
    *******************************************************************************/
   private List<List<String>> batches(List<String> paths)
   {
      List<List<String>> batches = new ArrayList<>();
      List<String> current = new ArrayList<>();
      int wildcards = 0;
      for(String path : paths)
      {
         boolean wildcard = path.endsWith("*");
         if(current.size() >= settings.maxPathsPerBatch() || (wildcard && wildcards >= settings.maxWildcardsPerBatch()))
         {
            batches.add(current);
            current = new ArrayList<>();
            wildcards = 0;
         }
         current.add(path);
         if(wildcard)
         {
            wildcards++;
         }
      }
      if(!current.isEmpty())
      {
         batches.add(current);
      }
      return batches;
   }
}
