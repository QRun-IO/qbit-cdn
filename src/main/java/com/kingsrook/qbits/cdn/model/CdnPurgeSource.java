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
 ** What asked for a purge.
 *******************************************************************************/
public enum CdnPurgeSource implements PossibleValueEnum<String>
{
   RECORD_CHANGE("RECORD_CHANGE", "Record change"),
   RECORD_ACTION("RECORD_ACTION", "Record action"),
   MANUAL("MANUAL", "Manual"),
   DEPLOY("DEPLOY", "Deploy"),
   RETRY("RETRY", "Retry");

   private final String id;
   private final String label;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   CdnPurgeSource(String id, String label)
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
