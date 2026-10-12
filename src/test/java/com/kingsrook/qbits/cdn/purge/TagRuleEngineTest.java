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
