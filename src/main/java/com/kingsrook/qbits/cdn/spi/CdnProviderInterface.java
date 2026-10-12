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


import java.util.Set;


/*******************************************************************************
 ** SPI every CDN adapter implements. Adapters are stateless: everything they
 ** need arrives in a CdnProviderConfig. See the design spec, QRun-IO/qqq
 ** docs/specs/2026-10-12-qbit-cdn-design.md section 4.1.
 *******************************************************************************/
public interface CdnProviderInterface
{
   /*******************************************************************************
    ** The provider this adapter drives.
    *******************************************************************************/
   CdnProviderType getType();

   /*******************************************************************************
    ** What this adapter can do for the given configuration.
    *******************************************************************************/
   Set<CdnCapability> getCapabilities(CdnProviderConfig config);

   /*******************************************************************************
    ** How responses should carry tags and targeted cache headers.
    *******************************************************************************/
   CdnHeaderDialect getHeaderDialect();

   /*******************************************************************************
    ** Send one already-planned batch.
    *******************************************************************************/
   CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch) throws CdnException;

   /*******************************************************************************
    ** Status of an asynchronous purge (CloudFront invalidations). Synchronous
    ** providers report SUCCEEDED.
    *******************************************************************************/
   default CdnPurgeResult checkPurge(CdnProviderConfig config, String providerRequestId) throws CdnException
   {
      return CdnPurgeResult.succeeded(providerRequestId);
   }
}
