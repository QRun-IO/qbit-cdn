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

package com.kingsrook.qbits.cdn.providers.cloudfront;


import java.util.List;


/*******************************************************************************
 ** Outcome of "Test connection" on a CloudFront provider row. Says whether the
 ** origin-verify header is configured, never its value.
 **
 ** @param ok                           credentials work and the IAM scope covers
 **                                     the distribution config and invalidations
 ** @param distributionDomainName       the dxxxx.cloudfront.net name, or null when
 **                                     GetDistribution is not permitted
 ** @param aliases                      alternate domain names
 ** @param originDomain                 the default behavior's origin domain
 ** @param originPort                   the port CloudFront uses on that origin
 ** @param originVerifyHeaderConfigured whether the origin sends the verify header
 ** @param status                       Deployed / InProgress, or null when unknown
 ** @param message                      a human-readable summary or the failure
 *******************************************************************************/
public record CloudFrontConnectionResult(boolean ok, String distributionDomainName, List<String> aliases, String originDomain, Integer originPort,
                                         boolean originVerifyHeaderConfigured, String status, String message)
{
   /*******************************************************************************
    ** A failed test.
    *******************************************************************************/
   public static CloudFrontConnectionResult failed(String message)
   {
      return new CloudFrontConnectionResult(false, null, List.of(), null, null, false, null, message);
   }
}
