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

package com.kingsrook.qbits.cdn.model;


import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;


/*******************************************************************************
 ** Lifecycle of a cdn_purge_request row.
 *******************************************************************************/
public enum CdnPurgeStatus implements PossibleValueEnum<String>
{
   PENDING("PENDING", "Pending"),
   MERGED("MERGED", "Merged"),
   SENT("SENT", "Sent"),
   IN_PROGRESS("IN_PROGRESS", "In progress"),
   SUCCEEDED("SUCCEEDED", "Succeeded"),
   FAILED("FAILED", "Failed (will retry)"),
   DEAD("DEAD", "Dead"),
   DEFERRED_BUDGET("DEFERRED_BUDGET", "Deferred (budget)"),
   SKIPPED_STAGE_OFF("SKIPPED_STAGE_OFF", "Not sent (hostnames not live)"),
   SKIPPED_NONE_PROVIDER("SKIPPED_NONE_PROVIDER", "Not sent (no CDN)"),
   SKIPPED_HUB_ONLY("SKIPPED_HUB_ONLY", "Not sent (hub pages only)");

   private final String id;
   private final String label;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   CdnPurgeStatus(String id, String label)
   {
      this.id = id;
      this.label = label;
   }



   /*******************************************************************************
    ** Getter for possibleValueId.
    *******************************************************************************/
   @Override
   public String getPossibleValueId()
   {
      return (id);
   }



   /*******************************************************************************
    ** Getter for possibleValueLabel.
    *******************************************************************************/
   @Override
   public String getPossibleValueLabel()
   {
      return (label);
   }
}
