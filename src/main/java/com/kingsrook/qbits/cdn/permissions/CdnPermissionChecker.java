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


import java.util.Map;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.actions.permissions.CustomPermissionChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.MetaDataWithPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;


/*******************************************************************************
 ** One checker for every qbit table, process, app and widget:
 ** - table reads, the app and widgets need cdn.view;
 ** - writes to config tables need cdn.admin; history tables
 **   ({@link CdnTables#READ_ONLY}) are never written through the UI or API;
 ** - each process needs the permission registered for it (default cdn.admin).
 *******************************************************************************/
public class CdnPermissionChecker implements CustomPermissionChecker
{
   /*******************************************************************************
    ** Check, throwing when denied.
    *******************************************************************************/
   @Override
   public void checkPermissionsThrowing(AbstractActionInput actionInput, MetaDataWithPermissionRules metaData) throws QPermissionDeniedException
   {
      String needed = required(actionInput, metaData);
      if(needed == null)
      {
         throw (new QPermissionDeniedException("This CDN table is written only by the CDN qbit."));
      }
      if(!has(needed))
      {
         throw (new QPermissionDeniedException("This needs the " + needed + " permission."));
      }
   }



   /*******************************************************************************
    ** The permission an action needs, or null when nobody may do it.
    *******************************************************************************/
   static String required(AbstractActionInput actionInput, MetaDataWithPermissionRules metaData)
   {
      boolean write = actionInput instanceof InsertInput || actionInput instanceof UpdateInput || actionInput instanceof DeleteInput;
      if(metaData instanceof QTableMetaData table)
      {
         if(!write)
         {
            return (CdnPermissions.VIEW);
         }
         return (CdnTables.READ_ONLY.contains(table.getName()) ? null : CdnPermissions.ADMIN);
      }
      if(metaData instanceof QProcessMetaData process)
      {
         Map<String, String> byProcess = CdnRuntime.processPermissions();
         return (byProcess.getOrDefault(process.getName(), CdnPermissions.ADMIN));
      }
      return (CdnPermissions.VIEW);
   }



   /*******************************************************************************
    ** Whether the current session holds a permission (or the instance is fully
    ** anonymous and the config allows that, for local development).
    *******************************************************************************/
   public static boolean has(String permission)
   {
      QInstance instance = QContext.getQInstance();
      QSession  session  = QContext.getQSession();
      if(session != null && session.hasPermission(permission))
      {
         return (true);
      }
      return (CdnRuntime.isOpenWhenAnonymous() && instance != null && instance.getAuthentication() != null
         && QAuthenticationType.FULLY_ANONYMOUS.equals(instance.getAuthentication().getType()));
   }
}
