package com.modmigrator.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MigrationResult {

    public enum Status {
        SUCCESS, PARTIAL_SUCCESS, NEEDS_MANUAL_WORK, FAILED
    }

    private Status status;
    private final List<MigrationIssue> issues = new ArrayList<>();
    private Path outputJar;
    private Path outputSourceDir;
    private Path reportPath;
    private String summary;
    private long processingTimeMs;

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public List<MigrationIssue> getIssues() { return issues; }
    public void addIssue(MigrationIssue issue) { issues.add(issue); }
    public Path getOutputJar() { return outputJar; }
    public void setOutputJar(Path outputJar) { this.outputJar = outputJar; }
    public Path getOutputSourceDir() { return outputSourceDir; }
    public void setOutputSourceDir(Path outputSourceDir) { this.outputSourceDir = outputSourceDir; }
    public Path getReportPath() { return reportPath; }
    public void setReportPath(Path reportPath) { this.reportPath = reportPath; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public long getErrorCount() {
        return issues.stream().filter(i -> i.getSeverity() == MigrationIssue.Severity.ERROR).count();
    }

    public long getWarningCount() {
        return issues.stream().filter(i -> i.getSeverity() == MigrationIssue.Severity.WARNING).count();
    }

    public long getAutoFixedCount() {
        return issues.stream().filter(MigrationIssue::isAutoFixed).count();
    }
}
