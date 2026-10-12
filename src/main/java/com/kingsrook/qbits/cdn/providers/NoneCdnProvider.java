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

package com.kingsrook.qbits.cdn.providers;


import java.util.Set;
import com.kingsrook.qbits.cdn.spi.CdnCapability;
import com.kingsrook.qbits.cdn.spi.CdnHeaderDialect;
import com.kingsrook.qbits.cdn.spi.CdnProviderConfig;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qbits.cdn.spi.CdnPurgeBatch;
import com.kingsrook.qbits.cdn.spi.CdnPurgeResult;


/*******************************************************************************
 ** No CDN yet. Headers are still correct, and purges are recorded as skipped,
 ** so the qbit can be installed (and its would-be purges reviewed) before any
 ** CDN exists.
 *******************************************************************************/
public class NoneCdnProvider implements CdnProviderInterface
{
   /*******************************************************************************
    ** Getter for type.
    *******************************************************************************/
   @Override
   public CdnProviderType getType()
   {
      return CdnProviderType.NONE;
   }



   /*******************************************************************************
    ** Every purge kind is accepted, because nothing is sent.
    *******************************************************************************/
   @Override
   public Set<CdnCapability> getCapabilities(CdnProviderConfig config)
   {
      return Set.of(CdnCapability.PURGE_URL, CdnCapability.PURGE_WILDCARD, CdnCapability.PURGE_TAG);
   }



   /*******************************************************************************
    ** No tag header.
    *******************************************************************************/
   @Override
   public CdnHeaderDialect getHeaderDialect()
   {
      return CdnHeaderDialect.NO_TAGS;
   }



   /*******************************************************************************
    ** Record, do not send.
    *******************************************************************************/
   @Override
   public CdnPurgeResult purge(CdnProviderConfig config, CdnPurgeBatch batch)
   {
      return CdnPurgeResult.skipped("No CDN configured: " + batch.items().size() + " " + batch.kind() + " item(s) not sent");
   }
}
