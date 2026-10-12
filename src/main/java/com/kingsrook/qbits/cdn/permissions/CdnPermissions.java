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

package com.kingsrook.qbits.cdn.permissions;


/*******************************************************************************
 ** Permission names (design spec section 4.8).
 *******************************************************************************/
public final class CdnPermissions
{
   /** The app and every table, read-only. */
   public static final String VIEW      = "cdn.view";
   /** Purge, warm and retry actions. */
   public static final String PURGE     = "cdn.purge";
   /** Whole-hostname / whole-distribution purges. */
   public static final String PURGE_ALL = "cdn.purgeAll";
   /** Connection and settings edits, tests, imports and drift checks. */
   public static final String ADMIN     = "cdn.admin";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnPermissions()
   {
   }
}
