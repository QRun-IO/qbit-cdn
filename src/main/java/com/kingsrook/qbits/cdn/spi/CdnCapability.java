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


/*******************************************************************************
 ** What a provider adapter can do. The qbit plans around missing capabilities
 ** (for example, tag purges become path invalidations on CloudFront).
 *******************************************************************************/
public enum CdnCapability
{
   PURGE_URL,
   PURGE_WILDCARD,
   PURGE_PREFIX,
   PURGE_TAG,
   PURGE_HOST,
   PURGE_ALL,
   SOFT_PURGE,
   SIGNED_URL,
   SIGNED_COOKIE,
   IMAGE_TRANSFORM,
   STATS_IMPORT,
   CONFIG_READ,
   HONORS_CDN_CACHE_CONTROL
}
