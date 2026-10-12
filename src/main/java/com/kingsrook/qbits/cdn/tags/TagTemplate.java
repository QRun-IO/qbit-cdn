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


import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*******************************************************************************
 ** Renders tag and path templates such as "post:{id}" or
 ** "/blog/{blog.slug}/{slug}*" from a record's values. Dotted names read
 ** joined values that the caller has flattened into the map ("blog.slug").
 ** A template with any missing or blank value renders to nothing, so a
 ** half-filled path is never purged.
 *******************************************************************************/
public final class TagTemplate
{
   private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)}");



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TagTemplate()
   {
   }



   /*******************************************************************************
    ** Render, or empty when a value is missing.
    *******************************************************************************/
   public static Optional<String> render(String template, Map<String, ?> values)
   {
      if(template == null || template.isBlank())
      {
         return Optional.empty();
      }
      Matcher matcher = PLACEHOLDER.matcher(template);
      StringBuilder out = new StringBuilder();
      while(matcher.find())
      {
         Object value = values == null ? null : values.get(matcher.group(1));
         if(value == null || value.toString().isBlank())
         {
            return Optional.empty();
         }
         matcher.appendReplacement(out, Matcher.quoteReplacement(value.toString()));
      }
      matcher.appendTail(out);
      return Optional.of(out.toString());
   }
}
