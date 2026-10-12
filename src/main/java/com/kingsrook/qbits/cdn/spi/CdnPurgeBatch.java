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


import java.util.List;
import java.util.Objects;


/*******************************************************************************
 ** One purge call, already sized for the provider's limits.
 **
 ** @param kind            what the items are (tags, paths, hosts)
 ** @param items           the tags, paths or hostnames (empty for ALL)
 ** @param callerReference idempotency key (the purge request id), so an
 **                        at-least-once retry never purges twice
 *******************************************************************************/
public record CdnPurgeBatch(CdnPurgeKind kind, List<String> items, String callerReference)
{
   /*******************************************************************************
    ** Validating constructor; items are copied.
    *******************************************************************************/
   public CdnPurgeBatch
   {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(callerReference, "callerReference");
      items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
   }
}
