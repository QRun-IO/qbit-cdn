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
 ** Outcome of one purge call.
 **
 ** @param state             where the purge is now
 ** @param providerRequestId the provider's id (CloudFront invalidation id), or null
 ** @param httpStatus        the provider's HTTP status, or null
 ** @param message           a human-readable detail, or null
 *******************************************************************************/
public record CdnPurgeResult(State state, String providerRequestId, Integer httpStatus, String message)
{
   /*******************************************************************************
    ** Purge lifecycle as reported by a provider.
    *******************************************************************************/
   public enum State
   {
      SUCCEEDED,
      IN_PROGRESS,
      FAILED_RETRYABLE,
      FAILED_PERMANENT,
      SKIPPED
   }



   /*******************************************************************************
    ** A finished purge.
    *******************************************************************************/
   public static CdnPurgeResult succeeded(String providerRequestId)
   {
      return new CdnPurgeResult(State.SUCCEEDED, providerRequestId, null, null);
   }



   /*******************************************************************************
    ** A purge that was intentionally not sent.
    *******************************************************************************/
   public static CdnPurgeResult skipped(String message)
   {
      return new CdnPurgeResult(State.SKIPPED, null, null, message);
   }
}
