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


import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qbits.cdn.processes.CdnProcesses;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


/*******************************************************************************
 ** Unit test for CdnPermissionChecker.
 *******************************************************************************/
class CdnPermissionCheckerTest
{
   /*******************************************************************************
    ** Required permissions per action and metadata.
    *******************************************************************************/
   @Test
   void requiredPermissions() throws Exception
   {
      QInstance instance = CdnTestSupport.setUp();
      assertEquals(CdnPermissions.VIEW, CdnPermissionChecker.required(new QueryInput(CdnTables.PROVIDER), instance.getTable(CdnTables.PROVIDER)));
      assertEquals(CdnPermissions.ADMIN, CdnPermissionChecker.required(new UpdateInput(CdnTables.PROVIDER), instance.getTable(CdnTables.PROVIDER)));
      assertNull(CdnPermissionChecker.required(new InsertInput(CdnTables.PURGE_REQUEST), instance.getTable(CdnTables.PURGE_REQUEST)));
      assertEquals(CdnPermissions.PURGE_ALL, CdnPermissionChecker.required(null, instance.getProcess(CdnProcesses.PURGE_HOSTNAME)));
      assertEquals(CdnPermissions.PURGE, CdnPermissionChecker.required(null, instance.getProcess(CdnProcesses.PURGE_PREFIX + "post")));
      assertEquals(CdnPermissions.VIEW, CdnPermissionChecker.required(null, instance.getWidget("cdnHome")));
   }



   /*******************************************************************************
    ** Sessions with and without the permission; anonymous dev mode.
    *******************************************************************************/
   @Test
   void sessions() throws Exception
   {
      QInstance instance = CdnTestSupport.setUp();
      CdnPermissionChecker checker = new CdnPermissionChecker();
      assertDoesNotThrow(() -> checker.checkPermissionsThrowing(new QueryInput(CdnTables.PROVIDER), instance.getTable(CdnTables.PROVIDER)));
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new InsertInput(CdnTables.PURGE_REQUEST), instance.getTable(CdnTables.PURGE_REQUEST)))
         .isInstanceOf(QPermissionDeniedException.class).hasMessageContaining("written only by the CDN qbit");

      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("mock").withType(QAuthenticationType.MOCK));
      QSession session = new QSession();
      QContext.init(instance, session);
      assertThatThrownBy(() -> checker.checkPermissionsThrowing(new UpdateInput(CdnTables.PROVIDER), instance.getTable(CdnTables.PROVIDER)))
         .isInstanceOf(QPermissionDeniedException.class).hasMessageContaining(CdnPermissions.ADMIN);
      session.withPermission(CdnPermissions.ADMIN);
      assertDoesNotThrow(() -> checker.checkPermissionsThrowing(new UpdateInput(CdnTables.PROVIDER), instance.getTable(CdnTables.PROVIDER)));
   }
}
