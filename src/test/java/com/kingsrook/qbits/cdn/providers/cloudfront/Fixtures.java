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

package com.kingsrook.qbits.cdn.providers.cloudfront;


import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;


/*******************************************************************************
 ** Loads hand-built fixtures from src/test/resources/fixtures.
 *******************************************************************************/
final class Fixtures
{
   /*******************************************************************************
    ** Not instantiable.
    *******************************************************************************/
   private Fixtures()
   {
   }



   /*******************************************************************************
    ** A fixture's text, for example "cloudfront/distribution-config.xml".
    *******************************************************************************/
   static String load(String name)
   {
      try(InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name))
      {
         if(in == null)
         {
            throw new IllegalArgumentException("No fixture " + name);
         }
         return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      catch(IOException e)
      {
         throw new UncheckedIOException(e);
      }
   }
}
