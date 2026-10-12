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
import java.util.List;


/*******************************************************************************
 ** Optional operations behind the Test Connection, Import Edge Stats and
 ** Check Config Drift actions. Adapters that support them implement this.
 *******************************************************************************/
public interface CdnOperations
{
   /*******************************************************************************
    ** Result of a connection test. Secrets never appear here.
    **
    ** @param ok                     whether everything checked out
    ** @param dnsTarget              what DNS should point at (distribution domain)
    ** @param originDomain           configured origin host
    ** @param originPort             configured origin port
    ** @param originHeaderConfigured whether the secret origin header is set
    ** @param status                 provider status (e.g. Deployed)
    ** @param message                human-readable detail
    *******************************************************************************/
   record ConnectionTest(boolean ok, String dnsTarget, String originDomain, Integer originPort, boolean originHeaderConfigured, String status,
                         String message)
   {
   }



   /*******************************************************************************
    ** One difference between the expected and the actual edge config.
    *******************************************************************************/
   record DriftItem(String area, String expected, String actual)
   {
   }



   /*******************************************************************************
    ** Read-only connection test.
    *******************************************************************************/
   ConnectionTest connectionTest(CdnProviderConfig config);

   /*******************************************************************************
    ** Daily edge statistics for a date range (inclusive).
    *******************************************************************************/
   List<CdnEdgeStat> importStats(CdnProviderConfig config, LocalDate from, LocalDate to) throws CdnException;

   /*******************************************************************************
    ** Differences between the expected config (JSON) and the provider's.
    *******************************************************************************/
   List<DriftItem> driftFindings(CdnProviderConfig config, String expectedJson) throws CdnException;
}
