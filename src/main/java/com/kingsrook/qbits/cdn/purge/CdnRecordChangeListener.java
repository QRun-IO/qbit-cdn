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

package com.kingsrook.qbits.cdn.purge;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.cdn.CdnRuntime;
import com.kingsrook.qbits.cdn.model.CdnPurgeSource;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeEvent;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeListenerInterface;
import com.kingsrook.qqq.backend.core.actions.tables.listeners.RecordChangeType;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Watches the purgeable tables: after a write commits, the enabled
 ** cdn_table_tag_rule rows for the table turn the change into purge requests.
 ** A failure here never fails the write; it is logged, and content still
 ** expires at the edge TTL.
 *******************************************************************************/
public class CdnRecordChangeListener implements RecordChangeListenerInterface
{
   private static final QLogger LOG = QLogger.getLogger(CdnRecordChangeListener.class);



   /*******************************************************************************
    ** Only the configured purgeable tables.
    *******************************************************************************/
   @Override
   public boolean appliesTo(String tableName, RecordChangeType type)
   {
      return (CdnRuntime.isInstalled() && !CdnTables.ALL.contains(tableName) && CdnRuntime.getConfig().getPurgeableTables().contains(tableName));
   }



   /*******************************************************************************
    ** Plan now (old and new values are at hand), record after commit.
    *******************************************************************************/
   @Override
   public void onRecordsChanged(RecordChangeEvent event) throws QException
   {
      if(event == null || event.getRecords() == null)
      {
         return;
      }
      try
      {
         List<QRecord> rules = rulesFor(event.getTableName());
         List<QRecord> current = event.getType() == RecordChangeType.DELETE ? nulls(event.getRecords().size()) : event.getRecords();
         List<QRecord> old = event.getType() == RecordChangeType.INSERT ? nulls(event.getRecords().size())
            : event.getType() == RecordChangeType.DELETE ? event.getRecords() : event.getOldRecords();
         List<CdnPurgeService.Request> requests = new ArrayList<>();
         TagRuleEngine engine = new TagRuleEngine();
         for(QRecord rule : rules)
         {
            TagRuleEngine.Result result = engine.evaluate(rule, current, old);
            if(!result.isEmpty())
            {
               requests.add(new CdnPurgeService.Request("TAG", result.tags(), result.paths(), false, CdnPurgeSource.RECORD_CHANGE,
                  event.getTableName(), result.recordIds().size() == 1 ? result.recordIds().get(0) : null));
            }
         }
         if(requests.isEmpty())
         {
            return;
         }
         Runnable work = () ->
         {
            for(CdnPurgeService.Request request : requests)
            {
               try
               {
                  CdnPurgeService.enqueue(request);
               }
               catch(Exception e)
               {
                  LOG.warn("Could not record a CDN purge request", e, logPair("table", request.sourceTable()));
               }
            }
         };
         if(event.getTransaction() != null)
         {
            event.getTransaction().addAfterCommitCallback(work);
         }
         else
         {
            work.run();
         }
      }
      catch(Exception e)
      {
         LOG.warn("Could not plan a CDN purge for a record change", e, logPair("table", event.getTableName()));
      }
   }



   /*******************************************************************************
    ** Enabled rules for a table.
    *******************************************************************************/
   static List<QRecord> rulesFor(String tableName) throws QException
   {
      return (new QueryAction().execute(new QueryInput(CdnTables.TABLE_TAG_RULE).withFilter(new QQueryFilter()
         .withCriteria(new QFilterCriteria("tableName", QCriteriaOperator.EQUALS, tableName))
         .withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)))).getRecords());
   }



   /*******************************************************************************
    ** A list of n nulls.
    *******************************************************************************/
   private static List<QRecord> nulls(int n)
   {
      List<QRecord> list = new ArrayList<>();
      for(int i = 0; i < n; i++)
      {
         list.add(null);
      }
      return (list);
   }
}
