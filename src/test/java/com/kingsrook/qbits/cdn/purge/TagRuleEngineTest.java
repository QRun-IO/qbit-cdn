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


import java.util.List;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qbits.cdn.CdnTestSupport;
import com.kingsrook.qbits.cdn.planning.InvalidationPath;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/*******************************************************************************
 ** Unit test for TagRuleEngine and PurgeText.
 *******************************************************************************/
class TagRuleEngineTest
{
   /*******************************************************************************
    ** Templates, joins, hubs and filters.
    *******************************************************************************/
   @Test
   void evaluatesRules() throws Exception
   {
      CdnTestSupport.setUp();
      CdnTestSupport.insert("blog", new QRecord().withValue("slug", "quick-bits"));
      QRecord rule = new QRecord().withValue("tagTemplates", "post:{id}\nbad tag\n").withValue("invalidationPaths", "/blog/{blog.slug}/{slug}*\nhub:/blog/{blog.slug}\nno-slash\n/x/{missing}")
         .withValue("publishedField", "isPublished");
      QRecord current = new QRecord().withValue("id", 5).withValue("slug", "new").withValue("blogId", 1).withValue("isPublished", true);
      QRecord old = new QRecord().withValue("id", 5).withValue("slug", "old").withValue("blogId", 1).withValue("isPublished", true);
      TagRuleEngine.Result result = new TagRuleEngine().evaluate(rule, List.of(current), List.of(old));
      assertThat(result.tags()).containsExactly("post:5");
      assertThat(result.paths()).containsExactly(InvalidationPath.leaf("/blog/quick-bits/new*"), InvalidationPath.hub("/blog/quick-bits"),
         InvalidationPath.leaf("/blog/quick-bits/old*"));
      assertThat(result.recordIds()).containsExactly(5);

      QRecord draft = new QRecord().withValue("id", 6).withValue("isPublished", false);
      assertTrue(new TagRuleEngine().evaluate(rule, List.of(draft), List.of(draft)).isEmpty());
      assertFalse(TagRuleEngine.counts(rule, null, null));
      assertTrue(TagRuleEngine.counts(new QRecord(), draft, null));
      assertFalse(TagRuleEngine.counts(new QRecord().withValue("onlyWhenFieldsChange", "slug"), current, new QRecord(current)));
   }


   /*******************************************************************************
    ** The joined table comes from the FK field's table possible-value source, so
    ** a prefixed table resolves {blog.slug}; a PVS named for a table also works.
    *******************************************************************************/
   @Test
   void resolvesPrefixedTableViaPossibleValueSource() throws Exception
   {
      CdnTestSupport.setUp();
      var instance = QContext.getQInstance();
      instance.addTable(new QTableMetaData().withName("cms_blog").withBackendName(CdnTestSupport.MEMORY).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("slug", QFieldType.STRING)));
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("cms_blog").withName("blogPvs"));
      instance.getTable("post").getFields().get("blogId").setPossibleValueSourceName("blogPvs");
      CdnTestSupport.insert("cms_blog", new QRecord().withValue("slug", "prefixed"));

      QRecord rule = new QRecord().withValue("tableName", "post").withValue("invalidationPaths", "/blog/{blog.slug}/{slug}*");
      QRecord post = new QRecord().withValue("id", 1).withValue("slug", "a").withValue("blogId", 1);
      assertThat(new TagRuleEngine().evaluate(rule, List.of(post), List.of()).paths()).containsExactly(InvalidationPath.leaf("/blog/prefixed/a*"));

      ////////////////////////////////////////////////////////////
      // a PVS whose name is itself a table (not TABLE-typed)   //
      ////////////////////////////////////////////////////////////
      instance.getTable("post").getFields().get("blogId").setPossibleValueSourceName("cms_blog");
      assertThat(TagRuleEngine.joinedTableName("post", "blog")).isEqualTo("cms_blog");
   }



   /*******************************************************************************
    ** No field, no PVS, or no source table: the bare prefix is the table name.
    *******************************************************************************/
   @Test
   void fallsBackToBarePrefix() throws Exception
   {
      CdnTestSupport.setUp();
      assertThat(TagRuleEngine.joinedTableName("post", "blog")).isEqualTo("blog");
      assertThat(TagRuleEngine.joinedTableName("post", "nofield")).isEqualTo("nofield");
      assertThat(TagRuleEngine.joinedTableName("nosuchtable", "blog")).isEqualTo("blog");
      assertThat(TagRuleEngine.joinedTableName(null, "blog")).isEqualTo("blog");
      CdnTestSupport.insert("blog", new QRecord().withValue("slug", "bare"));
      QRecord rule = new QRecord().withValue("tableName", "post").withValue("invalidationPaths", "/blog/{blog.slug}");
      QRecord post = new QRecord().withValue("id", 1).withValue("blogId", 1);
      assertThat(new TagRuleEngine().evaluate(rule, List.of(post), List.of()).paths()).containsExactly(InvalidationPath.leaf("/blog/bare"));
   }



   /*******************************************************************************
    ** A missing FK field value, or a missing joined table, skips the placeholder
    ** without throwing.
    *******************************************************************************/
   @Test
   void missingFieldOrTableIsSkipped() throws Exception
   {
      CdnTestSupport.setUp();
      QRecord rule = new QRecord().withValue("tableName", "post").withValue("invalidationPaths", "/blog/{blog.slug}\n/x/{ghost.slug}\n/y/{slug}");

      QRecord noFk = new QRecord().withValue("id", 1).withValue("slug", "s");
      assertThat(new TagRuleEngine().evaluate(rule, List.of(noFk), List.of()).paths()).containsExactly(InvalidationPath.leaf("/y/s"));

      QRecord dangling = new QRecord().withValue("id", 2).withValue("slug", "s").withValue("ghostId", 7);
      TagRuleEngine engine = new TagRuleEngine();
      assertThat(engine.evaluate(rule, List.of(dangling, dangling), List.of()).paths()).containsExactly(InvalidationPath.leaf("/y/s"));

      QContext.getQInstance().getTable("post").getFields().get("blogId").setPossibleValueSourceName("nowhere");
      QRecord toNowhere = new QRecord().withValue("id", 3).withValue("slug", "s").withValue("blogId", 1);
      assertThat(new TagRuleEngine().evaluate(rule, List.of(toNowhere), List.of()).paths()).containsExactly(InvalidationPath.leaf("/y/s"));
   }



   /*******************************************************************************
    ** Ignore-only rules compare actual values after merging partial updates.
    *******************************************************************************/
   @Test
   void ignoresCounterOnlyUpdates() throws Exception
   {
      QRecord rule = new QRecord().withValue("onlyWhenFieldsChange", " !viewCount, !lastIndexedAt ").withValue("tagTemplates", "post:{id}");
      QRecord old = new QRecord().withValue("id", 1).withValue("title", "Old").withValue("viewCount", 10).withValue("lastIndexedAt", "yesterday");
      TagRuleEngine engine = new TagRuleEngine();
      assertTrue(engine.evaluate(rule, List.of(new QRecord(old)), List.of(old)).isEmpty());
      assertTrue(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("viewCount", 11).withValue("lastIndexedAt", "today")), List.of(old)).isEmpty());
      assertTrue(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("title", "Old")), List.of(old)).isEmpty());
      assertThat(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("title", "New").withValue("viewCount", 11)), List.of(old)).tags()).containsExactly("post:1");
      assertFalse(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("title", null)), List.of(old)).isEmpty());
      assertFalse(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("newField", "value")), List.of(old)).isEmpty());
      assertFalse(engine.evaluate(rule, List.of(new QRecord().withValue("id", 1).withValue("ViewCount", 11)), List.of(old)).isEmpty());
   }



   /*******************************************************************************
    ** Plain lists retain allow-list behavior; ignored entries always win.
    *******************************************************************************/
   @Test
   void appliesAllowListAndMixedRules()
   {
      QRecord old = new QRecord().withValue("title", "Old").withValue("viewCount", 10);
      QRecord counterChange = new QRecord(old).withValue("viewCount", 11);
      QRecord titleChange = new QRecord(old).withValue("title", "New");
      QRecord rule = new QRecord().withValue("onlyWhenFieldsChange", " title, ,slug ");
      assertFalse(TagRuleEngine.counts(rule, counterChange, old));
      assertTrue(TagRuleEngine.counts(rule, titleChange, old));
      assertFalse(TagRuleEngine.counts(rule, new QRecord(old), old));
      rule.setValue("onlyWhenFieldsChange", "title, viewCount, !viewCount");
      assertFalse(TagRuleEngine.counts(rule, counterChange, old));
      assertTrue(TagRuleEngine.counts(rule, titleChange, old));
      assertFalse(TagRuleEngine.counts(rule, new QRecord(old).withValue("slug", "new"), old));
      rule.setValue("onlyWhenFieldsChange", "viewCount,!viewCount");
      assertFalse(TagRuleEngine.counts(rule, titleChange, old));
      assertFalse(TagRuleEngine.counts(rule, counterChange, old));
   }



   /*******************************************************************************
    ** Changed-field filters never suppress inserts or deletes.
    *******************************************************************************/
   @Test
   void insertsAndDeletesIgnoreFieldFilters() throws Exception
   {
      QRecord post = new QRecord().withValue("id", 1).withValue("viewCount", 10);
      for(String fields : List.of("!viewCount", "title", "title,!viewCount"))
      {
         QRecord rule = new QRecord().withValue("onlyWhenFieldsChange", fields).withValue("tagTemplates", "post:{id}");
         assertThat(new TagRuleEngine().evaluate(rule, List.of(post), List.of()).tags()).containsExactly("post:1");
         assertThat(new TagRuleEngine().evaluate(rule, List.of(), List.of(post)).tags()).containsExactly("post:1");
      }
   }



   /*******************************************************************************
    ** Stored text round-trips; invalid lines are dropped.
    *******************************************************************************/
   @Test
   void purgeText()
   {
      String text = PurgeText.paths(List.of(InvalidationPath.leaf("/a*"), InvalidationPath.hub("/b")));
      assertThat(text).isEqualTo("/a*\nhub:/b");
      assertThat(PurgeText.paths(text + "\nbad\n/c/*/d")).containsExactly(InvalidationPath.leaf("/a*"), InvalidationPath.hub("/b"));
      assertThat(PurgeText.tags(" post:1 \n\n blog:2 ")).containsExactly("post:1", "blog:2");
      assertThat(TagRuleEngine.lines(null)).isEmpty();
   }
}
