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

package com.kingsrook.qbits.cdn;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Configuration of the CDN qbit.
 *******************************************************************************/
public class CdnQBitConfig implements QBitConfig
{
   private String       backendName;
   private String       schedulerName;
   private List<String> purgeableTables       = new ArrayList<>();
   private boolean      openWhenAnonymous     = false;
   private int          dispatchIntervalSeconds = 5;
   private int          flushWindowSeconds    = 30;
   private int          maxAttempts           = 5;
   private String       appName               = "cdnAdmin";
   private String       appLabel              = "CDN";



   /*******************************************************************************
    ** Validate.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      assertCondition(StringUtils.hasContent(backendName), "CDN qbit: backendName is required", errors);
      assertCondition(dispatchIntervalSeconds > 0, "CDN qbit: dispatchIntervalSeconds must be positive", errors);
      assertCondition(flushWindowSeconds >= 0, "CDN qbit: flushWindowSeconds must not be negative", errors);
      assertCondition(maxAttempts > 0, "CDN qbit: maxAttempts must be positive", errors);
   }



   /*******************************************************************************
    ** Default backend for the qbit's tables.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Getter for backendName.
    *******************************************************************************/
   public String getBackendName()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Fluent setter for backendName (the backend that stores the cdn_ tables).
    *******************************************************************************/
   public CdnQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName.
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName (null: the scheduled processes are
    ** registered but not scheduled).
    *******************************************************************************/
   public CdnQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for purgeableTables.
    *******************************************************************************/
   public List<String> getPurgeableTables()
   {
      return (purgeableTables);
   }



   /*******************************************************************************
    ** Fluent setter for purgeableTables: the app tables that get the
    ** "Purge From CDN" action and whose changes are watched.
    *******************************************************************************/
   public CdnQBitConfig withPurgeableTables(List<String> purgeableTables)
   {
      this.purgeableTables = purgeableTables == null ? new ArrayList<>() : new ArrayList<>(purgeableTables);
      return (this);
   }



   /*******************************************************************************
    ** Getter for openWhenAnonymous.
    *******************************************************************************/
   public boolean isOpenWhenAnonymous()
   {
      return (openWhenAnonymous);
   }



   /*******************************************************************************
    ** Fluent setter for openWhenAnonymous (local development only).
    *******************************************************************************/
   public CdnQBitConfig withOpenWhenAnonymous(boolean openWhenAnonymous)
   {
      this.openWhenAnonymous = openWhenAnonymous;
      return (this);
   }



   /*******************************************************************************
    ** Getter for dispatchIntervalSeconds.
    *******************************************************************************/
   public int getDispatchIntervalSeconds()
   {
      return (dispatchIntervalSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for dispatchIntervalSeconds.
    *******************************************************************************/
   public CdnQBitConfig withDispatchIntervalSeconds(int dispatchIntervalSeconds)
   {
      this.dispatchIntervalSeconds = dispatchIntervalSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for flushWindowSeconds.
    *******************************************************************************/
   public int getFlushWindowSeconds()
   {
      return (flushWindowSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for flushWindowSeconds (default flush window; a provider's
    ** settings can override it).
    *******************************************************************************/
   public CdnQBitConfig withFlushWindowSeconds(int flushWindowSeconds)
   {
      this.flushWindowSeconds = flushWindowSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for maxAttempts.
    *******************************************************************************/
   public int getMaxAttempts()
   {
      return (maxAttempts);
   }



   /*******************************************************************************
    ** Fluent setter for maxAttempts (then DEAD).
    *******************************************************************************/
   public CdnQBitConfig withMaxAttempts(int maxAttempts)
   {
      this.maxAttempts = maxAttempts;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName.
    *******************************************************************************/
   public String getAppName()
   {
      return (appName);
   }



   /*******************************************************************************
    ** Fluent setter for appName.
    *******************************************************************************/
   public CdnQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appLabel.
    *******************************************************************************/
   public String getAppLabel()
   {
      return (appLabel);
   }



   /*******************************************************************************
    ** Fluent setter for appLabel.
    *******************************************************************************/
   public CdnQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }
}
