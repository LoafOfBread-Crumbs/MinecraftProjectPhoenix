package com.modmigrator.service;

import com.modmigrator.model.MigrationConfig;
import com.modmigrator.model.MigrationIssue;
import com.modmigrator.model.MigrationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportGenerator {

    public void generateHtmlReport(MigrationConfig config, MigrationResult result, Path outputPath) throws IOException {
        StringBuilder html = new StringBuilder();

        String statusColor = switch (result.getStatus()) {
            case SUCCESS -> "#22c55e";
            case PARTIAL_SUCCESS -> "#f59e0b";
            case NEEDS_MANUAL_WORK -> "#ef4444";
            case FAILED -> "#7f1d1d";
        };

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Mod Migration Report</title>
            <style>
              body { font-family: system-ui, sans-serif; background: #0f172a; color: #e2e8f0; margin: 0; padding: 0; }
              .header { background: #1e293b; padding: 2rem; border-bottom: 1px solid #334155; }
              .header h1 { margin: 0; font-size: 1.8rem; color: #f8fafc; }
              .header .subtitle { color: #94a3b8; margin-top: 0.5rem; }
              .container { max-width: 1200px; margin: 2rem auto; padding: 0 2rem; }
              .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 1rem; margin-bottom: 2rem; }
              .stat-card { background: #1e293b; border-radius: 0.75rem; padding: 1.25rem; border: 1px solid #334155; }
              .stat-card .value { font-size: 2rem; font-weight: 700; }
              .stat-card .label { color: #94a3b8; font-size: 0.875rem; margin-top: 0.25rem; }
              .status-badge { display: inline-block; padding: 0.5rem 1.25rem; border-radius: 2rem; font-weight: 600; margin-bottom: 1.5rem; }
              .issues { background: #1e293b; border-radius: 0.75rem; border: 1px solid #334155; overflow: hidden; }
              .issues h2 { margin: 0; padding: 1.25rem 1.5rem; border-bottom: 1px solid #334155; font-size: 1.1rem; }
              .issue { padding: 1rem 1.5rem; border-bottom: 1px solid #1e293b; }
              .issue:last-child { border-bottom: none; }
              .issue-header { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 0.5rem; }
              .badge { padding: 0.2rem 0.6rem; border-radius: 0.375rem; font-size: 0.75rem; font-weight: 600; }
              .badge-error { background: #450a0a; color: #fca5a5; }
              .badge-warning { background: #451a03; color: #fcd34d; }
              .badge-info { background: #0c1a2e; color: #7dd3fc; }
              .badge-fixed { background: #052e16; color: #86efac; }
              .issue-title { font-weight: 600; }
              .issue-detail { color: #94a3b8; font-size: 0.875rem; margin-bottom: 0.25rem; }
              .issue-location { font-family: monospace; font-size: 0.8rem; color: #64748b; }
              .issue-suggestion { font-size: 0.875rem; color: #67e8f9; margin-top: 0.375rem; }
              .even { background: #162032; }
            </style>
            </head>
            <body>
            """);

        html.append(String.format("""
            <div class="header">
              <h1>Mod Migration Report</h1>
              <div class="subtitle">Generated %s</div>
            </div>
            <div class="container">
            """,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

        String modName = config.getSourceModInfo().getModName() != null
            ? config.getSourceModInfo().getModName()
            : config.getSourceModInfo().getFileName();
        String sourceVer = config.getSourceModInfo().getDetectedMinecraftVersion() != null
            ? config.getSourceModInfo().getDetectedMinecraftVersion() : "Unknown";

        html.append(String.format("""
            <div class="stat-card" style="margin-bottom:1.5rem; background:#0f172a;">
              <div style="font-size:1.1rem; font-weight:600; color:#f8fafc;">%s</div>
              <div style="color:#94a3b8; margin-top:0.25rem;">%s → %s | %s</div>
            </div>
            """, modName, sourceVer, config.getTargetMinecraftVersion(),
            config.getSourceModInfo().getModLoader()));

        html.append(String.format("""
            <div class="status-badge" style="background: %s22; color: %s; border: 1px solid %s;">
              Status: %s
            </div>
            <p style="color:#94a3b8;">%s</p>
            """, statusColor, statusColor, statusColor,
            result.getStatus().toString().replace("_", " "), result.getSummary()));

        html.append(String.format("""
            <div class="stats">
              <div class="stat-card">
                <div class="value" style="color:#fca5a5;">%d</div>
                <div class="label">Errors</div>
              </div>
              <div class="stat-card">
                <div class="value" style="color:#fcd34d;">%d</div>
                <div class="label">Warnings</div>
              </div>
              <div class="stat-card">
                <div class="value" style="color:#86efac;">%d</div>
                <div class="label">Auto-Fixed</div>
              </div>
              <div class="stat-card">
                <div class="value" style="color:#7dd3fc;">%.1fs</div>
                <div class="label">Processing Time</div>
              </div>
            </div>
            """, result.getErrorCount(), result.getWarningCount(),
            result.getAutoFixedCount(), result.getProcessingTimeMs() / 1000.0));

        html.append("""
            <div class="issues">
              <h2>Issues & Findings</h2>
            """);

        List<MigrationIssue> issues = result.getIssues();
        for (int i = 0; i < issues.size(); i++) {
            MigrationIssue issue = issues.get(i);
            String rowClass = i % 2 == 0 ? "" : " even";
            String badgeClass = switch (issue.getSeverity()) {
                case ERROR -> "badge-error";
                case WARNING -> "badge-warning";
                case INFO -> "badge-info";
            };
            String severityLabel = issue.isAutoFixed() ? "AUTO-FIXED" : issue.getSeverity().toString();
            String badgeActualClass = issue.isAutoFixed() ? "badge-fixed" : badgeClass;

            html.append(String.format("""
                <div class="issue%s">
                  <div class="issue-header">
                    <span class="badge %s">%s</span>
                    <span class="badge" style="background:#1e293b; color:#94a3b8; border:1px solid #334155;">%s</span>
                    <span class="issue-title">%s</span>
                  </div>
                  <div class="issue-detail">%s</div>
                  <div class="issue-location">%s</div>
                  %s
                </div>
                """,
                rowClass, badgeActualClass, severityLabel,
                issue.getCategory().toString().replace("_", " "),
                escapeHtml(issue.getTitle()),
                escapeHtml(issue.getDetail()),
                escapeHtml(issue.getLocation()),
                issue.getSuggestion() != null && !issue.getSuggestion().isBlank()
                    ? "<div class=\"issue-suggestion\">💡 " + escapeHtml(issue.getSuggestion()) + "</div>"
                    : ""
            ));
        }

        html.append("""
              </div>
            </div>
            </body>
            </html>
            """);

        Files.writeString(outputPath, html.toString());
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
    }
}
