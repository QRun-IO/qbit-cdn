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


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;


/*******************************************************************************
 ** How tags and paths are stored in cdn_purge_request text fields: one per
 ** line; hub paths are prefixed "hub:".
 *******************************************************************************/
public final class PurgeText
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PurgeText()
   {
   }



   /*******************************************************************************
    ** Paths to text.
    *******************************************************************************/
   public static String paths(List<InvalidationPath> paths)
   {
      List<String> lines = new ArrayList<>();
      for(InvalidationPath path : paths)
      {
         lines.add(path.hub() ? "hub:" + path.path() : path.path());
      }
      return (String.join("\n", lines));
   }



   /*******************************************************************************
    ** Text to paths (invalid lines are skipped).
    *******************************************************************************/
   public static List<InvalidationPath> paths(String text)
   {
      List<InvalidationPath> paths = new ArrayList<>();
      for(String line : TagRuleEngine.lines(text))
      {
         boolean hub = line.startsWith("hub:");
         String path = hub ? line.substring(4).strip() : line;
         try
         {
            paths.add(new InvalidationPath(path, hub));
         }
         catch(IllegalArgumentException e)
         {
            //////////////////////////////////////
            // not a valid path: never sent on //
            //////////////////////////////////////
         }
      }
      return (paths);
   }



   /*******************************************************************************
    ** Text to tags.
    *******************************************************************************/
   public static List<String> tags(String text)
   {
      return (TagRuleEngine.lines(text));
   }
}
