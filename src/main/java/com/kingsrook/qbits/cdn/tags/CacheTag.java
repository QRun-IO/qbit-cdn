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


import java.util.regex.Pattern;


/*******************************************************************************
 ** Canonical, provider-neutral cache tags: kind:id[:facet], for example
 ** "post:123", "blog:7:list", "path:/blog/x". The same tag means the same
 ** thing in Next.js cacheTag(), in the CDN and in purge requests.
 *******************************************************************************/
public final class CacheTag
{
   /** Longest tag (ASCII characters); keeps every provider's limits. */
   public static final int MAX_LENGTH = 64;

   private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9-]*:[!-+\\--~]+$");



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CacheTag()
   {
   }



   /*******************************************************************************
    ** True when the tag is printable ASCII without commas, has a lower-case
    ** kind prefix, and fits the portable length.
    *******************************************************************************/
   public static boolean isValid(String tag)
   {
      return tag != null && tag.length() <= MAX_LENGTH && VALID.matcher(tag).matches();
   }
}
