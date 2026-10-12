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

package com.kingsrook.qbits.cdn.tags;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/*******************************************************************************
 ** Keeps the tags on one response within the strictest common provider limits
 ** (Bunny's 1 KB CDN-Tag header, Google's 50 tags per object): at most
 ** MAX_TAGS tags and MAX_BYTES bytes joined. When a response would exceed it,
 ** the fine-grained tags are dropped and only the caller's coarse tags are
 ** kept (a list response carries "blog:7:list" instead of fifty "post:*").
 *******************************************************************************/
public final class CacheTagBudget
{
   public static final int MAX_TAGS  = 20;
   public static final int MAX_BYTES = 1000;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CacheTagBudget()
   {
   }



   /*******************************************************************************
    ** The tags to emit: invalid tags dropped, duplicates removed, and only the
    ** coarse tags (as many as fit) when everything does not fit.
    *******************************************************************************/
   public static List<String> apply(List<String> fine, List<String> coarse)
   {
      Set<String> coarseValid = valid(coarse);
      Set<String> all = new LinkedHashSet<>(coarseValid);
      all.addAll(valid(fine));
      if(fits(all))
      {
         return new ArrayList<>(all);
      }
      List<String> kept = new ArrayList<>();
      for(String tag : coarseValid)
      {
         kept.add(tag);
         if(!fits(kept))
         {
            kept.removeLast();
            break;
         }
      }
      return kept;
   }



   /*******************************************************************************
    ** Valid, de-duplicated tags in order.
    *******************************************************************************/
   private static Set<String> valid(List<String> tags)
   {
      Set<String> result = new LinkedHashSet<>();
      if(tags != null)
      {
         for(String tag : tags)
         {
            if(CacheTag.isValid(tag))
            {
               result.add(tag);
            }
         }
      }
      return result;
   }



   /*******************************************************************************
    ** Whether a tag set fits the budget (joined with ", ").
    *******************************************************************************/
   static boolean fits(Collection<String> tags)
   {
      if(tags.size() > MAX_TAGS)
      {
         return false;
      }
      return String.join(", ", tags).getBytes(StandardCharsets.US_ASCII).length <= MAX_BYTES;
   }
}
