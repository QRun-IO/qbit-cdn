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
 ** Optional: an adapter that can say whether its CDN is healthy, beyond the
 ** "Test connection" action ({@link CdnOperations}). The central health check
 ** prefers this when an adapter implements it.
 *******************************************************************************/
public interface CdnHealthProbe
{
   /*******************************************************************************
    ** Probe the CDN read-only. Never throws: failures come back as DOWN or
    ** DEGRADED, with fixed messages (see {@link CdnHealth}).
    *******************************************************************************/
   CdnHealth checkHealth(CdnProviderConfig config);
}
