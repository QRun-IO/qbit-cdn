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

package com.kingsrook.qbits.cdn.tags;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Unit tests for CacheTag, CacheTagBudget and TagTemplate.
 *******************************************************************************/
class CacheTagsTest
{
   /*******************************************************************************
    ** Tag syntax.
    *******************************************************************************/
   @Test
   void tagValidity()
   {
      assertThat(CacheTag.isValid("post:123")).isTrue();
      assertThat(CacheTag.isValid("blog:7:list")).isTrue();
      assertThat(CacheTag.isValid("path:/blog/quick-bits/my-post")).isTrue();
      assertThat(CacheTag.isValid("Post:1")).isFalse();
      assertThat(CacheTag.isValid("post")).isFalse();
      assertThat(CacheTag.isValid("post:a,b")).isFalse();
      assertThat(CacheTag.isValid("post:a b")).isFalse();
      assertThat(CacheTag.isValid("post:" + "x".repeat(60))).isFalse();
      assertThat(CacheTag.isValid(null)).isFalse();
   }



   /*******************************************************************************
    ** Within the budget everything is kept, de-duplicated, coarse first.
    *******************************************************************************/
   @Test
   void budgetKeepsWhatFits()
   {
      assertThat(CacheTagBudget.apply(List.of("post:1", "post:2", "post:1", "BAD"), List.of("blog:7:list")))
         .containsExactly("blog:7:list", "post:1", "post:2");
      assertThat(CacheTagBudget.apply(null, null)).isEmpty();
   }



   /*******************************************************************************
    ** A list response over the budget keeps only its coarse tags.
    *******************************************************************************/
   @Test
   void budgetCollapsesToCoarseTags()
   {
      List<String> posts = new ArrayList<>();
      for(int i = 0; i < 50; i++)
      {
         posts.add("post:" + i);
      }
      assertThat(CacheTagBudget.apply(posts, List.of("blog:7:list", "site:1"))).containsExactly("blog:7:list", "site:1");

      List<String> coarse = new ArrayList<>();
      for(int i = 0; i < 25; i++)
      {
         coarse.add("blog:" + i + ":list");
      }
      assertThat(CacheTagBudget.apply(List.of(), coarse)).hasSize(CacheTagBudget.MAX_TAGS);
   }



   /*******************************************************************************
    ** Templates render from flattened values and refuse half-filled output.
    *******************************************************************************/
   @Test
   void templates()
   {
      Map<String, Object> post = Map.of("id", 123, "slug", "my-post", "blog.slug", "quick-bits", "title", " ");
      assertThat(TagTemplate.render("post:{id}", post)).contains("post:123");
      assertThat(TagTemplate.render("/blog/{blog.slug}/{slug}*", post)).contains("/blog/quick-bits/my-post*");
      assertThat(TagTemplate.render("/x/{missing}", post)).isEmpty();
      assertThat(TagTemplate.render("/x/{title}", post)).isEmpty();
      assertThat(TagTemplate.render("site:all", null)).contains("site:all");
      assertThat(TagTemplate.render(" ", post)).isEmpty();
      assertThat(TagTemplate.render("/a/{id}", Map.of("id", "$1\\x"))).contains("/a/$1\\x");
   }
}
