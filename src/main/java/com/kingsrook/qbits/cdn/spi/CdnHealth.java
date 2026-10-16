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

package com.kingsrook.qbits.cdn.spi;


import java.util.Map;


/*******************************************************************************
 ** Result of an adapter's own health probe (see {@link CdnHealthProbe}). The
 ** message and every detail value must be fixed text, numbers, booleans or
 ** known provider states: never provider-supplied free text, hostnames, ids
 ** or credentials, because they are copied to the central health tables.
 **
 ** @param state   UP, DEGRADED or DOWN
 ** @param message a short fixed summary
 ** @param details safe facts (may be empty, never null)
 *******************************************************************************/
public record CdnHealth(State state, String message, Map<String, Object> details)
{
   /*******************************************************************************
    ** Health as the adapter sees it.
    *******************************************************************************/
   public enum State
   {
      UP,
      DEGRADED,
      DOWN
   }



   /*******************************************************************************
    ** Null details become an empty map.
    *******************************************************************************/
   public CdnHealth
   {
      details = details == null ? Map.of() : Map.copyOf(details);
   }
}
