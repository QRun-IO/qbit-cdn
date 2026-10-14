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

package com.kingsrook.qbits.cdn.model;


import java.util.List;
import com.kingsrook.qbits.cdn.CdnQBitConfig;
import com.kingsrook.qbits.cdn.permissions.CdnPermissionChecker;
import com.kingsrook.qbits.cdn.spi.CdnProviderType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.PossibleValueEnum;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The qbit's tables: standard, visible QQQ tables named cdn_* (lowercase
 ** snake_case). Field names are camelCase, as everywhere in QQQ.
 *******************************************************************************/
public final class CdnTables
{
   public static final String PROVIDER          = "cdn_provider";
   public static final String HOSTNAME          = "cdn_hostname";
   public static final String CACHE_POLICY      = "cdn_cache_policy";
   public static final String TABLE_TAG_RULE    = "cdn_table_tag_rule";
   public static final String PURGE_REQUEST     = "cdn_purge_request";
   public static final String REVALIDATION_HOOK = "cdn_revalidation_hook";
   public static final String EDGE_STAT_DAILY   = "cdn_edge_stat_daily";
   public static final String ORIGIN_CHECK      = "cdn_origin_check";
   public static final String DRIFT_FINDING     = "cdn_drift_finding";

   /** Tables that hold history written only by the qbit (read-only in the UI). */
   public static final List<String> READ_ONLY = List.of(PURGE_REQUEST, EDGE_STAT_DAILY, ORIGIN_CHECK, DRIFT_FINDING);

   public static final List<String> ALL = List.of(PROVIDER, HOSTNAME, CACHE_POLICY, TABLE_TAG_RULE, PURGE_REQUEST, REVALIDATION_HOOK,
      EDGE_STAT_DAILY, ORIGIN_CHECK, DRIFT_FINDING);

   public static final String PVS_PROVIDER_TYPE    = "cdn_provider_type";
   public static final String PVS_STAGE            = "cdn_hostname_stage";
   public static final String PVS_PURGE_STATUS     = "cdn_purge_status";
   public static final String PVS_PURGE_SOURCE     = "cdn_purge_source";
   public static final String PVS_SIGNATURE_FORMAT = "cdn_signature_format";
   public static final String PVS_POLICY_CLASS     = "cdn_policy_class";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnTables()
   {
   }



   /*******************************************************************************
    ** Permission rules shared by every qbit table, app, widget and process.
    *******************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules()
         .withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
         .withPermissionBaseName("cdn")
         .withCustomPermissionChecker(new QCodeReference(CdnPermissionChecker.class)));
   }



   /*******************************************************************************
    ** The possible-value sources for the enums and table references.
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      return (List.of(
         QPossibleValueSource.newForEnum(PVS_PROVIDER_TYPE, ProviderTypeValue.values()),
         QPossibleValueSource.newForEnum(PVS_STAGE, CdnHostnameStage.values()),
         QPossibleValueSource.newForEnum(PVS_PURGE_STATUS, CdnPurgeStatus.values()),
         QPossibleValueSource.newForEnum(PVS_PURGE_SOURCE, CdnPurgeSource.values()),
         QPossibleValueSource.newForEnum(PVS_POLICY_CLASS, CdnPolicyClass.values()),
         QPossibleValueSource.newForEnum(PVS_SIGNATURE_FORMAT, CdnSignatureFormat.values()),
         QPossibleValueSource.newForTable(PROVIDER),
         QPossibleValueSource.newForTable(HOSTNAME)));
   }



   /*******************************************************************************
    ** Provider types as possible values.
    *******************************************************************************/
   public enum ProviderTypeValue implements PossibleValueEnum<String>
   {
      CLOUDFRONT(CdnProviderType.CLOUDFRONT, "Amazon CloudFront"),
      CLOUDFLARE(CdnProviderType.CLOUDFLARE, "Cloudflare"),
      FASTLY(CdnProviderType.FASTLY, "Fastly"),
      BUNNY(CdnProviderType.BUNNY, "Bunny.net"),
      SOUIN(CdnProviderType.SOUIN, "Souin (self-hosted)"),
      VARNISH(CdnProviderType.VARNISH, "Varnish (self-hosted)"),
      NONE(CdnProviderType.NONE, "No CDN");

      private final CdnProviderType type;
      private final String          label;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      ProviderTypeValue(CdnProviderType type, String label)
      {
         this.type = type;
         this.label = label;
      }



      /*******************************************************************************
       ** Getter for possibleValueId.
       *******************************************************************************/
      @Override
      public String getPossibleValueId()
      {
         return (type.name());
      }



      /*******************************************************************************
       ** Getter for possibleValueLabel.
       *******************************************************************************/
      @Override
      public String getPossibleValueLabel()
      {
         return (label);
      }
   }



   /*******************************************************************************
    ** All tables.
    *******************************************************************************/
   public static List<QTableMetaData> tables(CdnQBitConfig config)
   {
      return (List.of(provider(config), hostname(config), cachePolicy(config), tableTagRule(config), purgeRequest(config),
         revalidationHook(config), edgeStatDaily(config), originCheck(config), driftFinding(config)));
   }



   /*******************************************************************************
    ** A table with the common id/createDate/modifyDate fields.
    *******************************************************************************/
   private static QTableMetaData base(CdnQBitConfig config, String name, String label, String icon)
   {
      return (new QTableMetaData()
         .withName(name)
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withBackendName(config.getBackendName())
         .withPrimaryKeyField("id")
         .withPermissionRules(permissionRules())
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withIsEditable(false))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME).withIsEditable(false))
         .withField(new QFieldMetaData("modifyDate", QFieldType.DATE_TIME).withIsEditable(false)));
   }



   /*******************************************************************************
    ** A read-only field.
    *******************************************************************************/
   private static QFieldMetaData ro(String name, QFieldType type)
   {
      return (new QFieldMetaData(name, type).withIsEditable(false));
   }



   /*******************************************************************************
    ** cdn_provider: one row per CDN. Secrets never live here.
    *******************************************************************************/
   static QTableMetaData provider(CdnQBitConfig config)
   {
      return (base(config, PROVIDER, "CDN Provider", "cloud")
         .withRecordLabelFormatAndFields("%s", "name")
         .withField(new QFieldMetaData("name", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("providerType", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName(PVS_PROVIDER_TYPE))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(new QFieldMetaData("accountRef", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("zoneOrDistributionId", QFieldType.STRING).withMaxLength(200))
         .withField(new QFieldMetaData("secretRef", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("settingsJson", QFieldType.TEXT))
         .withField(new QFieldMetaData("dnsTarget", QFieldType.STRING).withMaxLength(255))
         .withField(new QFieldMetaData("originHost", QFieldType.STRING).withMaxLength(255))
         .withField(new QFieldMetaData("credentialRotatedDate", QFieldType.DATE))
         .withField(ro("maskedKeyId", QFieldType.STRING))
         .withField(ro("lastHealthStatus", QFieldType.STRING))
         .withField(ro("lastHealthMessage", QFieldType.TEXT))
         .withField(ro("lastHealthCheckDate", QFieldType.DATE_TIME))
         .withField(ro("lastSuccessfulTestDate", QFieldType.DATE_TIME)));
   }



   /*******************************************************************************
    ** cdn_hostname: hostnames behind a provider, with their rollout stage.
    *******************************************************************************/
   static QTableMetaData hostname(CdnQBitConfig config)
   {
      return (base(config, HOSTNAME, "CDN Hostname", "dns")
         .withRecordLabelFormatAndFields("%s", "hostname")
         .withField(new QFieldMetaData("hostname", QFieldType.STRING).withIsRequired(true).withMaxLength(255))
         .withField(new QFieldMetaData("cdnProviderId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(PROVIDER))
         .withField(new QFieldMetaData("hostnameRole", QFieldType.STRING).withMaxLength(20))
         .withField(new QFieldMetaData("stage", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName(PVS_STAGE).withDefaultValue(CdnHostnameStage.OFF.name()))
         .withField(new QFieldMetaData("purgeEnabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(new QFieldMetaData("notes", QFieldType.TEXT)));
   }



   /*******************************************************************************
    ** cdn_cache_policy: header policy per hostname/path or route class.
    *******************************************************************************/
   static QTableMetaData cachePolicy(CdnQBitConfig config)
   {
      return (base(config, CACHE_POLICY, "CDN Cache Policy", "policy")
         .withRecordLabelFormatAndFields("%s", "name")
         .withField(new QFieldMetaData("name", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("cdnHostnameId", QFieldType.INTEGER).withPossibleValueSourceName(HOSTNAME))
         .withField(new QFieldMetaData("matchType", QFieldType.STRING).withIsRequired(true).withMaxLength(20))
         .withField(new QFieldMetaData("matchValue", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(new QFieldMetaData("policyClass", QFieldType.STRING).withIsRequired(true).withPossibleValueSourceName(PVS_POLICY_CLASS))
         .withField(new QFieldMetaData("browserMaxAge", QFieldType.INTEGER))
         .withField(new QFieldMetaData("edgeMaxAge", QFieldType.INTEGER))
         .withField(new QFieldMetaData("staleWhileRevalidate", QFieldType.INTEGER))
         .withField(new QFieldMetaData("staleIfError", QFieldType.INTEGER))
         .withField(new QFieldMetaData("immutable", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("noStore", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("priority", QFieldType.INTEGER).withDefaultValue(100))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(ro("seeded", QFieldType.BOOLEAN)));
   }



   /*******************************************************************************
    ** cdn_table_tag_rule: how a table's changes become tags and paths.
    *******************************************************************************/
   static QTableMetaData tableTagRule(CdnQBitConfig config)
   {
      return (base(config, TABLE_TAG_RULE, "CDN Table Tag Rule", "sell")
         .withRecordLabelFormatAndFields("%s", "tableName")
         .withField(new QFieldMetaData("tableName", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("tagTemplates", QFieldType.TEXT))
         .withField(new QFieldMetaData("invalidationPaths", QFieldType.TEXT))
         .withField(new QFieldMetaData("onlyWhenFieldsChange", QFieldType.STRING).withMaxLength(1000))
         .withField(new QFieldMetaData("publishedField", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withDefaultValue(true)));
   }



   /*******************************************************************************
    ** cdn_purge_request: every purge, written only by the qbit.
    *******************************************************************************/
   static QTableMetaData purgeRequest(CdnQBitConfig config)
   {
      return (base(config, PURGE_REQUEST, "CDN Purge Request", "cleaning_services")
         .withRecordLabelFormatAndFields("Purge %s", "id")
         .withField(ro("cdnProviderId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(ro("kind", QFieldType.STRING))
         .withField(ro("tags", QFieldType.TEXT))
         .withField(ro("paths", QFieldType.TEXT))
         .withField(ro("includeHubs", QFieldType.BOOLEAN))
         .withField(ro("billablePaths", QFieldType.INTEGER))
         .withField(ro("source", QFieldType.STRING).withPossibleValueSourceName(PVS_PURGE_SOURCE))
         .withField(ro("sourceTable", QFieldType.STRING))
         .withField(ro("sourceRecordId", QFieldType.STRING))
         .withField(ro("requestedBy", QFieldType.STRING))
         .withField(ro("status", QFieldType.STRING).withPossibleValueSourceName(PVS_PURGE_STATUS))
         .withField(ro("mergedIntoId", QFieldType.INTEGER))
         .withField(ro("attempts", QFieldType.INTEGER))
         .withField(ro("nextAttemptDate", QFieldType.DATE_TIME))
         .withField(ro("providerRequestId", QFieldType.STRING))
         .withField(ro("errorMessage", QFieldType.TEXT))
         .withField(ro("sentDate", QFieldType.DATE_TIME))
         .withField(ro("completedDate", QFieldType.DATE_TIME)));
   }



   /*******************************************************************************
    ** cdn_revalidation_hook: caches refreshed before the CDN (Next.js pods).
    *******************************************************************************/
   static QTableMetaData revalidationHook(CdnQBitConfig config)
   {
      return (base(config, REVALIDATION_HOOK, "CDN Revalidation Hook", "sync")
         .withRecordLabelFormatAndFields("%s", "name")
         .withField(new QFieldMetaData("name", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("url", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(new QFieldMetaData("fanOutDns", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("secretRef", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("signatureFormat", QFieldType.STRING).withPossibleValueSourceName(PVS_SIGNATURE_FORMAT).withDefaultValue(CdnSignatureFormat.STANDARD_WEBHOOKS.name()))
         .withField(new QFieldMetaData("allowPlainHttp", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(new QFieldMetaData("internalAllowlist", QFieldType.TEXT))
         .withField(new QFieldMetaData("timeoutMs", QFieldType.INTEGER).withDefaultValue(5000))
         .withField(new QFieldMetaData("enabled", QFieldType.BOOLEAN).withDefaultValue(true)));
   }



   /*******************************************************************************
    ** cdn_edge_stat_daily: imported edge statistics.
    *******************************************************************************/
   static QTableMetaData edgeStatDaily(CdnQBitConfig config)
   {
      return (base(config, EDGE_STAT_DAILY, "CDN Edge Stat", "insights")
         .withRecordLabelFormatAndFields("%s", "statDate")
         .withField(ro("cdnProviderId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(ro("scope", QFieldType.STRING))
         .withField(ro("statDate", QFieldType.DATE))
         .withField(ro("requests", QFieldType.LONG))
         .withField(ro("bytesDownloaded", QFieldType.LONG))
         .withField(ro("cacheHitRate", QFieldType.DECIMAL))
         .withField(ro("error4xxRate", QFieldType.DECIMAL))
         .withField(ro("error5xxRate", QFieldType.DECIMAL))
         .withField(ro("importedDate", QFieldType.DATE_TIME)));
   }



   /*******************************************************************************
    ** cdn_origin_check: origin header coverage checks.
    *******************************************************************************/
   static QTableMetaData originCheck(CdnQBitConfig config)
   {
      return (base(config, ORIGIN_CHECK, "CDN Origin Check", "fact_check")
         .withRecordLabelFormatAndFields("%s", "scope")
         .withField(ro("checkDate", QFieldType.DATE_TIME))
         .withField(ro("scope", QFieldType.STRING))
         .withField(ro("objectsChecked", QFieldType.INTEGER))
         .withField(ro("correct", QFieldType.INTEGER))
         .withField(ro("missingOrWrong", QFieldType.INTEGER))
         .withField(ro("unreadable", QFieldType.INTEGER)));
   }



   /*******************************************************************************
    ** cdn_drift_finding: expected edge config vs what the provider reports.
    *******************************************************************************/
   static QTableMetaData driftFinding(CdnQBitConfig config)
   {
      return (base(config, DRIFT_FINDING, "CDN Drift Finding", "compare_arrows")
         .withRecordLabelFormatAndFields("%s", "area")
         .withField(ro("cdnProviderId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(ro("checkedDate", QFieldType.DATE_TIME))
         .withField(ro("area", QFieldType.STRING))
         .withField(ro("expected", QFieldType.TEXT))
         .withField(ro("actual", QFieldType.TEXT))
         .withField(ro("severity", QFieldType.STRING))
         .withField(ro("resolvedDate", QFieldType.DATE_TIME)));
   }
}
