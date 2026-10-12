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


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;
import com.kingsrook.qbits.cdn.tags.CacheTag;
import com.kingsrook.qbits.cdn.tags.TagTemplate;
import com.kingsrook.qqq.backend.core.actions.tables.GetAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.get.GetInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.StringUtils;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Turns changed records into canonical tags and candidate invalidation paths
 ** using a cdn_table_tag_rule row:
 ** - tagTemplates: one template per line, e.g. "post:{id}";
 ** - invalidationPaths: one path template per line, e.g.
 **   "/blog/{blog.slug}/{slug}*"; a "hub:" prefix marks a hub path that
 **   expires by TTL instead of being invalidated;
 ** - publishedField: when set, only records that are (or were) published
 **   count, so draft edits purge nothing;
 ** - onlyWhenFieldsChange: comma list; an update that changes none of them
 **   purges nothing.
 **
 ** A dotted placeholder "{blog.slug}" reads field "slug" of the record that
 ** field "blogId" points to in table "blog" (the xId convention).
 *******************************************************************************/
public class TagRuleEngine
{
   private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)\\.([A-Za-z0-9_]+)}");

   private final Map<String, QRecord> joinCache = new HashMap<>();



   /*******************************************************************************
    ** Tags and paths for one table's changed records.
    **
    ** @param rule       the cdn_table_tag_rule row
    ** @param records    new versions (null entries for deletes)
    ** @param oldRecords old versions (null entries for inserts), same order
    *******************************************************************************/
   public Result evaluate(QRecord rule, List<QRecord> records, List<QRecord> oldRecords) throws QException
   {
      Set<String>                 tags  = new LinkedHashSet<>();
      Set<InvalidationPath>       paths = new LinkedHashSet<>();
      List<Serializable>          ids   = new ArrayList<>();
      int size = Math.max(records == null ? 0 : records.size(), oldRecords == null ? 0 : oldRecords.size());
      for(int i = 0; i < size; i++)
      {
         QRecord current = records == null || i >= records.size() ? null : records.get(i);
         QRecord old     = oldRecords == null || i >= oldRecords.size() ? null : oldRecords.get(i);
         if(current != null && old != null)
         {
            ///////////////////////////////////////////////////////////////
            // an update record may carry only the changed fields: lay  //
            // it over the old record so unchanged fields keep values   //
            ///////////////////////////////////////////////////////////////
            QRecord merged = new QRecord(old);
            current.getValues().forEach(merged::setValue);
            current = merged;
         }
         if(!counts(rule, current, old))
         {
            continue;
         }
         for(QRecord version : new QRecord[] { current, old })
         {
            if(version == null)
            {
               continue;
            }
            Map<String, Object> values = valuesFor(rule, version);
            for(String template : lines(rule.getValueString("tagTemplates")))
            {
               TagTemplate.render(template, values).filter(CacheTag::isValid).ifPresent(tags::add);
            }
            for(String template : lines(rule.getValueString("invalidationPaths")))
            {
               boolean hub  = template.startsWith("hub:");
               String  body = hub ? template.substring(4).strip() : template;
               TagTemplate.render(body, values).filter(path -> path.startsWith("/")).ifPresent(path -> paths.add(new InvalidationPath(path, hub)));
            }
         }
         Serializable id = current != null ? current.getValue("id") : old.getValue("id");
         if(id != null)
         {
            ids.add(id);
         }
      }
      return (new Result(new ArrayList<>(tags), new ArrayList<>(paths), ids));
   }



   /*******************************************************************************
    ** Whether a change counts under the rule's published and changed-field
    ** filters.
    *******************************************************************************/
   static boolean counts(QRecord rule, QRecord current, QRecord old)
   {
      if(current == null && old == null)
      {
         return (false);
      }
      String publishedField = rule.getValueString("publishedField");
      if(StringUtils.hasContent(publishedField))
      {
         boolean nowPublished = current != null && Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(current.getValue(publishedField)));
         boolean wasPublished = old != null && Boolean.TRUE.equals(ValueUtils.getValueAsBoolean(old.getValue(publishedField)));
         if(!nowPublished && !wasPublished)
         {
            return (false);
         }
      }
      String watched = rule.getValueString("onlyWhenFieldsChange");
      if(current != null && old != null && StringUtils.hasContent(watched))
      {
         return (Arrays.stream(watched.split(",")).map(String::strip).filter(StringUtils::hasContent)
            .anyMatch(field -> !Objects.equals(current.getValue(field), old.getValue(field))));
      }
      return (true);
   }



   /*******************************************************************************
    ** The record's values plus any dotted (joined) values its templates use.
    *******************************************************************************/
   Map<String, Object> valuesFor(QRecord rule, QRecord record) throws QException
   {
      Map<String, Object> values = new HashMap<>(record.getValues());
      String templates = rule.getValueString("tagTemplates") + "\n" + rule.getValueString("invalidationPaths");
      Matcher matcher = PLACEHOLDER.matcher(templates);
      while(matcher.find())
      {
         String joined = matcher.group(1);
         String field  = matcher.group(2);
         Serializable foreignKey = record.getValue(joined + "Id");
         if(foreignKey == null)
         {
            continue;
         }
         QRecord target = lookup(joined, foreignKey);
         if(target != null)
         {
            values.put(joined + "." + field, target.getValue(field));
         }
      }
      return (values);
   }



   /*******************************************************************************
    ** One joined record, cached for the life of this engine.
    *******************************************************************************/
   private QRecord lookup(String tableName, Serializable id) throws QException
   {
      String key = tableName + "#" + id;
      if(joinCache.containsKey(key))
      {
         return (joinCache.get(key));
      }
      QTableMetaData table = QContext.getQInstance().getTable(tableName);
      QRecord found = table == null ? null : new GetAction().executeForRecord(new GetInput(tableName).withPrimaryKey(id));
      joinCache.put(key, found);
      return (found);
   }



   /*******************************************************************************
    ** Non-blank, trimmed lines.
    *******************************************************************************/
   static List<String> lines(String text)
   {
      if(text == null)
      {
         return (List.of());
      }
      return (Arrays.stream(text.split("\\r?\\n")).map(String::strip).filter(StringUtils::hasContent).toList());
   }



   /*******************************************************************************
    ** Tags, candidate paths and the ids of the records that counted.
    *******************************************************************************/
   public record Result(List<String> tags, List<InvalidationPath> paths, List<Serializable> recordIds)
   {
      /*******************************************************************************
       ** Whether there is anything to purge.
       *******************************************************************************/
      public boolean isEmpty()
      {
         return (tags.isEmpty() && paths.isEmpty());
      }
   }
}
