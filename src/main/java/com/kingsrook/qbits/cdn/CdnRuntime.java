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

package com.kingsrook.qbits.cdn;


import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.kingsrook.qbits.cdn.providers.NoneCdnProvider;
import com.kingsrook.qbits.cdn.spi.CdnProviderInterface;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.exceptions.QException;


/*******************************************************************************
 ** Process-wide state of the qbit: the config, the provider registry (type to
 ** adapter factory), per-process permissions, and a clock (replaceable in
 ** tests).
 *******************************************************************************/
public final class CdnRuntime
{
   private static final Map<CdnProviderType, Supplier<CdnProviderInterface>> PROVIDERS           = new ConcurrentHashMap<>();
   private static final Map<String, String>                                  PROCESS_PERMISSIONS = new ConcurrentHashMap<>();

   private static volatile CdnQBitConfig            config;
   private static volatile java.util.function.Function<String, String> environment = System::getenv;
   private static volatile Clock         clock = Clock.systemUTC();

   static
   {
      PROVIDERS.put(CdnProviderType.NONE, NoneCdnProvider::new);
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnRuntime()
   {
   }



   /*******************************************************************************
    ** Install the config (called by the producer).
    *******************************************************************************/
   public static void install(CdnQBitConfig newConfig)
   {
      config = newConfig;
      registerKnownAdapters();
   }



   /*******************************************************************************
    ** The installed config, or null when the qbit is not installed.
    *******************************************************************************/
   public static CdnQBitConfig getConfig()
   {
      return (config);
   }



   /*******************************************************************************
    ** Whether the qbit is installed.
    *******************************************************************************/
   public static boolean isInstalled()
   {
      return (config != null);
   }



   /*******************************************************************************
    ** Whether permissions are open on fully anonymous instances (local dev).
    *******************************************************************************/
   public static boolean isOpenWhenAnonymous()
   {
      return (config != null && config.isOpenWhenAnonymous());
   }



   /*******************************************************************************
    ** Register (or replace) an adapter factory.
    *******************************************************************************/
   public static void registerProvider(CdnProviderType type, Supplier<CdnProviderInterface> factory)
   {
      PROVIDERS.put(type, factory);
   }



   /*******************************************************************************
    ** A new adapter for a provider type.
    *******************************************************************************/
   public static CdnProviderInterface provider(CdnProviderType type) throws QException
   {
      Supplier<CdnProviderInterface> factory = PROVIDERS.get(type);
      if(factory == null)
      {
         throw (new QException("No CDN adapter is registered for " + type));
      }
      return (factory.get());
   }



   /*******************************************************************************
    ** Adapters shipped with the qbit, registered by class name so a missing
    ** optional dependency only disables that adapter.
    *******************************************************************************/
   private static void registerKnownAdapters()
   {
      register(CdnProviderType.CLOUDFRONT, "com.kingsrook.qbits.cdn.providers.cloudfront.CloudFrontCdnProvider");
      register(CdnProviderType.SOUIN, "com.kingsrook.qbits.cdn.providers.souin.SouinCdnProvider");
   }



   /*******************************************************************************
    ** Register one adapter class when it is on the classpath.
    *******************************************************************************/
   private static void register(CdnProviderType type, String className)
   {
      if(PROVIDERS.containsKey(type))
      {
         return;
      }
      try
      {
         Class<?> adapterClass = Class.forName(className);
         PROVIDERS.put(type, () ->
         {
            try
            {
               return ((CdnProviderInterface) adapterClass.getDeclaredConstructor().newInstance());
            }
            catch(ReflectiveOperationException e)
            {
               throw (new IllegalStateException("Cannot create " + className, e));
            }
         });
      }
      catch(ClassNotFoundException | LinkageError e)
      {
         //////////////////////////////////////////////////////////////
         // adapter not on the classpath: that provider type is off //
         //////////////////////////////////////////////////////////////
      }
   }



   /*******************************************************************************
    ** The permission each qbit process needs.
    *******************************************************************************/
   public static Map<String, String> processPermissions()
   {
      return (PROCESS_PERMISSIONS);
   }



   /*******************************************************************************
    ** The clock (UTC unless replaced).
    *******************************************************************************/
   public static Clock clock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** Replace the clock (tests); null restores the system UTC clock.
    *******************************************************************************/
   public static void setClock(Clock newClock)
   {
      clock = newClock == null ? Clock.systemUTC() : newClock;
   }



   /*******************************************************************************
    ** An environment variable (replaceable in tests).
    *******************************************************************************/
   public static String environment(String name)
   {
      return (environment.apply(name));
   }



   /*******************************************************************************
    ** Replace the environment lookup (tests); null restores System.getenv.
    *******************************************************************************/
   public static void setEnvironment(java.util.function.Function<String, String> lookup)
   {
      environment = lookup == null ? System::getenv : lookup;
   }
}
