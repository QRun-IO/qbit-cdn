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


import java.time.LocalDate;
import java.util.Objects;


/*******************************************************************************
 ** One day of edge statistics for one provider (UTC day). Rates are
 ** percentages (0-100) as the provider reports them, or null when the
 ** provider had no datapoint for that metric (for example CloudFront's
 ** CacheHitRate, which only exists when additional metrics are enabled).
 **
 ** @param date            the UTC day
 ** @param requests        viewer requests
 ** @param bytesDownloaded bytes sent to viewers
 ** @param error4xxRate    percentage of 4xx responses, or null
 ** @param error5xxRate    percentage of 5xx responses, or null
 ** @param cacheHitRate    percentage of cache hits, or null
 *******************************************************************************/
public record CdnEdgeStat(LocalDate date, long requests, long bytesDownloaded, Double error4xxRate, Double error5xxRate, Double cacheHitRate)
{
   /*******************************************************************************
    ** Validating constructor.
    *******************************************************************************/
   public CdnEdgeStat
   {
      Objects.requireNonNull(date, "date");
   }
}
