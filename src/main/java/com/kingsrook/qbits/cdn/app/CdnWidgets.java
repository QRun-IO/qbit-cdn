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

package com.kingsrook.qbits.cdn.app;


import java.util.List;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The Admin -> CDN app: a home widget (the single owner of CDN data), four
 ** sections of standard tables, and a status card for the app's Health page.
 ** Processes are never added to the nav.
 *******************************************************************************/
public final class CdnWidgets
{
   public static final String HOME_WIDGET        = "cdnHome";
   public static final String HEALTH_CARD_WIDGET = "cdnHealthCard";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnWidgets()
   {
   }



   /*******************************************************************************
    ** The widgets.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      return (List.of(
         new QWidgetMetaData().withName(HOME_WIDGET).withLabel("CDN").withType(WidgetType.HTML.getType())
            .withCodeReference(new QCodeReference(HomeRenderer.class)).withGridColumns(12).withIsCard(false).withPermissionRules(CdnTables.permissionRules()),
         new QWidgetMetaData().withName(HEALTH_CARD_WIDGET).withLabel("CDN").withType(WidgetType.HTML.getType())
            .withCodeReference(new QCodeReference(HealthCardRenderer.class)).withGridColumns(12).withIsCard(true).withPermissionRules(CdnTables.permissionRules())));
   }



   /*******************************************************************************
    ** The app.
    *******************************************************************************/
   public static QAppMetaData app(CdnQBitConfig config, List<QTableMetaData> tables)
   {
      QAppMetaData app = new QAppMetaData()
         .withName(config.getAppName())
         .withLabel(config.getAppLabel())
         .withIcon(new QIcon("cloud"))
         .withPermissionRules(CdnTables.permissionRules())
         .withWidgets(List.of(HOME_WIDGET))
         .withSection(new QAppSection().withName("connection").withLabel("Connection").withIcon(new QIcon("cloud"))
            .withTables(List.of(CdnTables.PROVIDER, CdnTables.HOSTNAME)))
         .withSection(new QAppSection().withName("settings").withLabel("Settings").withIcon(new QIcon("tune"))
            .withTables(List.of(CdnTables.CACHE_POLICY, CdnTables.TABLE_TAG_RULE)))
         .withSection(new QAppSection().withName("activity").withLabel("Activity").withIcon(new QIcon("history"))
            .withTables(List.of(CdnTables.PURGE_REQUEST, CdnTables.REVALIDATION_HOOK)))
         .withSection(new QAppSection().withName("performance").withLabel("Performance").withIcon(new QIcon("insights"))
            .withTables(List.of(CdnTables.EDGE_STAT_DAILY, CdnTables.ORIGIN_CHECK, CdnTables.DRIFT_FINDING)));
      for(QTableMetaData table : tables)
      {
         app.withChild(table);
      }
      return (app);
   }



   /*******************************************************************************
    ** Path of the app (for links).
    *******************************************************************************/
   public static String appPath()
   {
      return (CdnRuntime.isInstalled() ? CdnRuntime.getConfig().getAppPath() : "/cdnAdmin");
   }



   /*******************************************************************************
    ** CDN home renderer.
    *******************************************************************************/
   public static class HomeRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(new RawHTML(null, CdnHomeView.render(CdnHomeData.load(), appPath()))));
      }
   }



   /*******************************************************************************
    ** Health status card renderer.
    *******************************************************************************/
   public static class HealthCardRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(new RawHTML("CDN", CdnHomeView.renderHealthCard(CdnHomeData.load(), appPath()))));
      }
   }
}
