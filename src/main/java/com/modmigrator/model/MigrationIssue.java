package com.modmigrator.model;

public class MigrationIssue {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public enum Category {
        REMAPPING,
        REMOVED_API,
        CHANGED_SIGNATURE,
        DEPRECATED_API,
        MISSING_DEPENDENCY,
        AUTO_FIXED,
        MANUAL_REQUIRED
    }

    private final Severity severity;
    private final Category category;
    private final String title;
    private final String detail;
    private final String location;
    private final String suggestion;
    private boolean autoFixed;

    public MigrationIssue(Severity severity, Category category, String title,
                          String detail, String location, String suggestion) {
        this.severity = severity;
        this.category = category;
        this.title = title;
        this.detail = detail;
        this.location = location;
        this.suggestion = suggestion;
        this.autoFixed = false;
    }

    public Severity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public String getLocation() { return location; }
    public String getSuggestion() { return suggestion; }
    public boolean isAutoFixed() { return autoFixed; }
    public void setAutoFixed(boolean autoFixed) { this.autoFixed = autoFixed; }

    @Override
    public String toString() {
        return String.format("[%s] %s — %s", severity, title, location);
    }
}
