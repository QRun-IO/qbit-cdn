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

package com.kingsrook.qbits.cdn.spi;


import java.util.List;


/*******************************************************************************
 ** How a provider reads cache tags and targeted cache headers.
 **
 ** @param tagHeaderName         response header carrying tags, or null when the
 **                              provider has no tag purge (then no tag header is
 **                              emitted, so nothing leaks to visitors)
 ** @param tagSeparator          separator between tags in that header
 ** @param edgeStripsTagHeader   whether the edge removes the tag header before
 **                              the response reaches the visitor
 ** @param honorsCdnCacheControl whether CDN-Cache-Control (RFC 9213) is honored
 *******************************************************************************/
public record CdnHeaderDialect(String tagHeaderName, String tagSeparator, boolean edgeStripsTagHeader, boolean honorsCdnCacheControl)
{
   /** No tag header and no CDN-Cache-Control (CloudFront, Azure Front Door, NONE). */
   public static final CdnHeaderDialect NO_TAGS = new CdnHeaderDialect(null, null, true, false);



   /*******************************************************************************
    ** True when tags should be written to responses at all.
    *******************************************************************************/
   public boolean emitsTags()
   {
      return tagHeaderName != null;
   }



   /*******************************************************************************
    ** The tag header value for already-budgeted tags, or null when none.
    *******************************************************************************/
   public String formatTags(List<String> tags)
   {
      if(!emitsTags() || tags == null || tags.isEmpty())
      {
         return null;
      }
      return String.join(tagSeparator, tags);
   }
}
