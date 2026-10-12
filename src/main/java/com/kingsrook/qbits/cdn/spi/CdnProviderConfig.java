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
import java.util.Objects;


/*******************************************************************************
 ** What an adapter needs to talk to one configured provider. Secrets are
 ** never here: secretRef names the environment variable (or secret store key)
 ** the adapter reads them from.
 **
 ** @param name                 the cdnProvider row's name
 ** @param type                 provider type
 ** @param zoneOrDistributionId zone / distribution / service id
 ** @param secretRef            where the credentials live, never their value
 ** @param settings             adapter settings (budget limits, windows, ...)
 *******************************************************************************/
public record CdnProviderConfig(String name, CdnProviderType type, String zoneOrDistributionId, String secretRef, Map<String, String> settings)
{
   /*******************************************************************************
    ** Validating constructor; settings are copied.
    *******************************************************************************/
   public CdnProviderConfig
   {
      Objects.requireNonNull(type, "type");
      settings = Map.copyOf(Objects.requireNonNullElse(settings, Map.of()));
   }



   /*******************************************************************************
    ** An integer setting, or the default when absent or not a number.
    *******************************************************************************/
   public int intSetting(String key, int defaultValue)
   {
      String value = settings.get(key);
      if(value == null)
      {
         return defaultValue;
      }
      try
      {
         return Integer.parseInt(value.strip());
      }
      catch(NumberFormatException e)
      {
         return defaultValue;
      }
   }
}
