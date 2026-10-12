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


import java.util.Objects;


/*******************************************************************************
 ** A candidate path to invalidate.
 **
 ** @param path the path, starting with "/"; a trailing "*" makes it a wildcard
 ** @param hub  true for hub pages (lists, feeds, home) that expire by a short
 **             TTL instead of being invalidated
 *******************************************************************************/
public record InvalidationPath(String path, boolean hub)
{
   /*******************************************************************************
    ** Validating constructor.
    *******************************************************************************/
   public InvalidationPath
   {
      Objects.requireNonNull(path, "path");
      if(!path.startsWith("/"))
      {
         throw new IllegalArgumentException("Invalidation paths start with /: " + path);
      }
      int star = path.indexOf('*');
      if(star >= 0 && star != path.length() - 1)
      {
         throw new IllegalArgumentException("A wildcard may only be the last character: " + path);
      }
   }



   /*******************************************************************************
    ** A leaf path (invalidated on change).
    *******************************************************************************/
   public static InvalidationPath leaf(String path)
   {
      return new InvalidationPath(path, false);
   }



   /*******************************************************************************
    ** A hub path (expires by TTL).
    *******************************************************************************/
   public static InvalidationPath hub(String path)
   {
      return new InvalidationPath(path, true);
   }
}
