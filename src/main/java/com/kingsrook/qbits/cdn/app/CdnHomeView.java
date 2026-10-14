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

package com.kingsrook.qbits.cdn.app;


import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.cdn.model.CdnTables;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** Renders the CDN home as one responsive HTML block: cards stack to a single
 ** column on narrow screens, tables use the full card width, and long values
 ** wrap instead of scrolling. Every card has an honest empty state.
 *******************************************************************************/
public final class CdnHomeView
{
   private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

   private static final String GRID  = "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,22rem),1fr));gap:1rem;";
   private static final String CARD  = "border:1px solid rgba(127,127,127,.3);border-radius:8px;padding:1rem;min-width:0;overflow-wrap:anywhere;";
   private static final String LIST  = "display:grid;grid-template-columns:minmax(8rem,auto) 1fr;gap:.25rem .75rem;margin:0 0 .75rem;font-size:.9rem;";
   private static final String ITEMS = "margin:0 0 .75rem;padding-left:1.25rem;font-size:.9rem;";
   private static final String MUTED = "opacity:.75;";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CdnHomeView()
   {
   }



   /*******************************************************************************
    ** The whole home; appPath here is the prefix of table and record links.
    *******************************************************************************/
   public static String render(CdnHomeData data, String appPath)
   {
      StringBuilder html = new StringBuilder("<div class=\"cdn-home\" style=\"" + GRID + "\">");
      connection(html, data, appPath);
      performance(html, data);
      purges(html, data, appPath);
      budget(html, data);
      origin(html, data);
      drift(html, data, appPath);
      return (html.append("</div>").toString());
   }



   /*******************************************************************************
    ** The Health status card.
    *******************************************************************************/
   public static String renderHealthCard(CdnHomeData data, String appPath)
   {
      String state = data.overallState();
      String text = "All CDN checks are OK.";
      if("NOT_CONNECTED".equals(state))
      {
         text = "No CDN provider configured: purges are recorded only.";
      }
      else if("ATTENTION".equals(state))
      {
         text = "Needs attention: see the CDN home.";
      }
      return ("<div style=\"" + CARD + "\"><strong>CDN</strong><p>" + esc(text) + "</p><a href=\"" + esc(appPath) + "\">Open the CDN home</a></div>");
   }



   /*******************************************************************************
    ** Connection card.
    *******************************************************************************/
   private static void connection(StringBuilder html, CdnHomeData data, String appPath)
   {
      open(html, "Connection");
      if(!data.isConnected())
      {
         empty(html, "No CDN provider configured: purges are recorded only. The CloudFront distribution is created in the cutover window.");
      }
      for(CdnHomeData.ProviderView provider : data.providers())
      {
         if(!provider.isConnected())
         {
            continue;
         }
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Provider", provider.name() + " (" + provider.type() + ")");
         row(html, "Distribution", value(provider.distributionId()));
         row(html, "Point DNS at", value(provider.dnsTarget()));
         row(html, "Origin", value(provider.originHost()));
         row(html, "Last good test", provider.lastSuccessfulTest() == null ? "Never" : WHEN.format(provider.lastSuccessfulTest()));
         if(provider.lastHealthStatus() != null && !"OK".equals(provider.lastHealthStatus()))
         {
            row(html, "Last test", provider.lastHealthStatus() + (provider.lastHealthMessage() == null ? "" : ": " + provider.lastHealthMessage()));
         }
         row(html, "Credential age", provider.credentialAgeDays() == null ? "Not recorded"
            : provider.credentialAgeDays() + " days (rotate every " + CdnHomeData.ROTATION_DAYS + ")"
            + (provider.credentialAgeDays() >= CdnHomeData.ROTATION_WARNING_DAYS ? " - rotation due" : ""));
         html.append("</dl>");
      }
      if(!data.hostnames().isEmpty())
      {
         html.append("<ul style=\"").append(ITEMS).append("\">");
         for(CdnHomeData.HostnameView hostname : data.hostnames())
         {
            html.append("<li>").append(esc(hostname.hostname())).append(": ").append(esc(hostname.stage())).append("</li>");
         }
         html.append("</ul>");
      }
      link(html, appPath, CdnTables.PROVIDER, "Providers");
      close(html);
   }



   /*******************************************************************************
    ** Performance card.
    *******************************************************************************/
   private static void performance(StringBuilder html, CdnHomeData data)
   {
      open(html, "Performance");
      if(data.stats().isEmpty())
      {
         empty(html, "No edge statistics yet. They are imported daily once a provider is connected.");
      }
      for(CdnHomeData.StatView stat : data.stats())
      {
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Provider", stat.provider());
         row(html, "Requests (7 / 30 days)", String.format("%,d / %,d", stat.requests7d(), stat.requests30d()));
         row(html, "Data served (7 / 30 days)", bytes(stat.bytes7d()) + " / " + bytes(stat.bytes30d()));
         row(html, "Cache hit rate (7 days)", stat.hitRate7d() == null ? "Not available (additional metrics are off)" : percent(stat.hitRate7d()));
         row(html, "5xx rate (7 days)", stat.error5xx7d() == null ? "Not reported" : percent(stat.error5xx7d()));
         html.append("</dl>").append(sparkline(stat.dailyRequests()));
      }
      close(html);
   }



   /*******************************************************************************
    ** Purge activity card.
    *******************************************************************************/
   private static void purges(StringBuilder html, CdnHomeData data, String appPath)
   {
      open(html, "Purge activity");
      html.append("<p>Failed: ").append(data.failed24h()).append(" in 24 h, ").append(data.failed7d()).append(" in 7 days. Dead: ")
         .append(data.dead24h()).append(" in 24 h, ").append(data.dead7d()).append(" in 7 days.</p>");
      if(data.recentPurges().isEmpty())
      {
         empty(html, "No purges recorded yet.");
      }
      else
      {
         html.append("<ul style=\"").append(ITEMS).append("\">");
         for(QRecord row : data.recentPurges())
         {
            Instant created = ValueUtils.getValueAsInstant(row.getValue("createDate"));
            html.append("<li><a href=\"").append(esc(appPath + "/" + CdnTables.PURGE_REQUEST + "/" + row.getValue("id"))).append("\">#")
               .append(esc(String.valueOf(row.getValue("id")))).append("</a> ").append(esc(row.getValueString("status")))
               .append(data.isConnected() ? "" : " - recorded only (not sent)")
               .append(created == null ? "" : ", " + WHEN.format(created)).append("</li>");
         }
         html.append("</ul>");
      }
      link(html, appPath, CdnTables.PURGE_REQUEST, "All purge requests");
      close(html);
   }



   /*******************************************************************************
    ** Invalidation budget card.
    *******************************************************************************/
   private static void budget(StringBuilder html, CdnHomeData data)
   {
      open(html, "Invalidation budget");
      if(data.budgets().isEmpty())
      {
         empty(html, "No path-billed provider connected yet.");
      }
      for(CdnHomeData.BudgetView budget : data.budgets())
      {
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Provider", budget.provider());
         row(html, "Paths billed this month", budget.billed() == 0 ? "No invalidations sent this month." : String.valueOf(budget.billed()));
         row(html, "Soft / hard limit", budget.soft() + " / " + budget.hard() + " (" + budget.free() + " free)");
         row(html, "Projected for the month", String.valueOf(budget.projected()));
         html.append("</dl>");
      }
      close(html);
   }



   /*******************************************************************************
    ** Origin header card.
    *******************************************************************************/
   private static void origin(StringBuilder html, CdnHomeData data)
   {
      open(html, "Origin headers");
      if(data.originChecks().isEmpty())
      {
         empty(html, "Origin header checks are not run by this version; nothing has been checked.");
      }
      for(QRecord check : data.originChecks())
      {
         Instant when = ValueUtils.getValueAsInstant(check.getValue("checkDate"));
         html.append("<p><strong>").append(esc(check.getValueString("scope"))).append("</strong>: ").append(esc(String.valueOf(check.getValue("correct"))))
            .append(" of ").append(esc(String.valueOf(check.getValue("objectsChecked")))).append(" objects have the right Cache-Control")
            .append(when == null ? "" : " (checked " + WHEN.format(when) + ")").append(".</p>");
      }
      close(html);
   }



   /*******************************************************************************
    ** Config drift card.
    *******************************************************************************/
   private static void drift(StringBuilder html, CdnHomeData data, String appPath)
   {
      open(html, "Config drift");
      if(data.lastDriftCheck() == null)
      {
         empty(html, "Nothing to compare yet. Drift is checked once the distribution exists.");
      }
      else if(data.openDriftByArea().isEmpty())
      {
         html.append("<p>No open findings (last check ").append(WHEN.format(data.lastDriftCheck())).append(").</p>");
      }
      else
      {
         html.append("<dl style=\"").append(LIST).append("\">");
         for(Map.Entry<String, Integer> entry : data.openDriftByArea().entrySet())
         {
            row(html, entry.getKey(), entry.getValue() + " open");
         }
         html.append("</dl>");
      }
      link(html, appPath, CdnTables.DRIFT_FINDING, "Drift findings");
      close(html);
   }



   /*******************************************************************************
    ** Inline SVG sparkline of daily values (scales with the card width).
    *******************************************************************************/
   static String sparkline(List<Long> values)
   {
      if(values == null || values.size() < 2)
      {
         return ("");
      }
      long max = Math.max(1, values.stream().mapToLong(Long::longValue).max().orElse(1));
      StringBuilder points = new StringBuilder();
      for(int i = 0; i < values.size(); i++)
      {
         points.append(i * 100.0 / (values.size() - 1)).append(',').append(30 - values.get(i) * 28.0 / max).append(' ');
      }
      return ("<svg viewBox=\"0 0 100 30\" preserveAspectRatio=\"none\" style=\"width:100%;height:2.5rem\" role=\"img\" aria-label=\"Daily requests\">"
         + "<polyline fill=\"none\" stroke=\"currentColor\" stroke-width=\"1\" vector-effect=\"non-scaling-stroke\" points=\"" + points.toString().strip() + "\"/></svg>");
   }



   /*******************************************************************************
    ** Card start.
    *******************************************************************************/
   private static void open(StringBuilder html, String title)
   {
      html.append("<section style=\"").append(CARD).append("\"><h3 style=\"margin-top:0\">").append(esc(title)).append("</h3>");
   }



   /*******************************************************************************
    ** Card end.
    *******************************************************************************/
   private static void close(StringBuilder html)
   {
      html.append("</section>");
   }



   /*******************************************************************************
    ** Empty-state text.
    *******************************************************************************/
   private static void empty(StringBuilder html, String text)
   {
      html.append("<p style=\"").append(MUTED).append("\">").append(esc(text)).append("</p>");
   }



   /*******************************************************************************
    ** A label/value row.
    *******************************************************************************/
   private static void row(StringBuilder html, String label, String value)
   {
      html.append("<dt style=\"font-weight:600\">").append(esc(label)).append("</dt><dd style=\"margin:0\">").append(esc(value)).append("</dd>");
   }



   /*******************************************************************************
    ** A link to a table in the app.
    *******************************************************************************/
   private static void link(StringBuilder html, String appPath, String table, String label)
   {
      html.append("<p><a href=\"").append(esc(appPath + "/" + table)).append("\">").append(esc(label)).append("</a></p>");
   }



   /*******************************************************************************
    ** "Not set" for blanks.
    *******************************************************************************/
   private static String value(String value)
   {
      return (value == null || value.isBlank() ? "Not set" : value);
   }



   /*******************************************************************************
    ** Human bytes.
    *******************************************************************************/
   static String bytes(long bytes)
   {
      String[] units = { "B", "KB", "MB", "GB", "TB" };
      double value = bytes;
      int unit = 0;
      while(value >= 1024 && unit < units.length - 1)
      {
         value /= 1024;
         unit++;
      }
      return (unit == 0 ? bytes + " B" : String.format("%.1f %s", value, units[unit]));
   }



   /*******************************************************************************
    ** Rate (0-100 or 0-1) as a percentage.
    *******************************************************************************/
   static String percent(BigDecimal rate)
   {
      BigDecimal value = rate.compareTo(BigDecimal.ONE) <= 0 && rate.scale() > 0 && rate.compareTo(BigDecimal.ZERO) >= 0 && rate.doubleValue() < 1.0001
         && rate.doubleValue() <= 1 ? rate.multiply(BigDecimal.valueOf(100)) : rate;
      return (value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%");
   }



   /*******************************************************************************
    ** HTML escape.
    *******************************************************************************/
   static String esc(String text)
   {
      if(text == null)
      {
         return ("");
      }
      return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"));
   }
}
