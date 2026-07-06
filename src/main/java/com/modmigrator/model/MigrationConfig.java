package com.modmigrator.model;

import java.nio.file.Path;

public class MigrationConfig {

    private ModInfo sourceModInfo;
    private String targetMinecraftVersion;
    private Path outputDirectory;
    private boolean attemptAutoFix;
    private boolean generateReport;
    private boolean decompileSource;

    public MigrationConfig() {
        this.attemptAutoFix = true;
        this.generateReport = true;
        this.decompileSource = true;
    }

    public ModInfo getSourceModInfo() { return sourceModInfo; }
    public void setSourceModInfo(ModInfo sourceModInfo) { this.sourceModInfo = sourceModInfo; }
    public String getTargetMinecraftVersion() { return targetMinecraftVersion; }
    public void setTargetMinecraftVersion(String targetMinecraftVersion) { this.targetMinecraftVersion = targetMinecraftVersion; }
    public Path getOutputDirectory() { return outputDirectory; }
    public void setOutputDirectory(Path outputDirectory) { this.outputDirectory = outputDirectory; }
    public boolean isAttemptAutoFix() { return attemptAutoFix; }
    public void setAttemptAutoFix(boolean attemptAutoFix) { this.attemptAutoFix = attemptAutoFix; }
    public boolean isGenerateReport() { return generateReport; }
    public void setGenerateReport(boolean generateReport) { this.generateReport = generateReport; }
    public boolean isDecompileSource() { return decompileSource; }
    public void setDecompileSource(boolean decompileSource) { this.decompileSource = decompileSource; }
}
