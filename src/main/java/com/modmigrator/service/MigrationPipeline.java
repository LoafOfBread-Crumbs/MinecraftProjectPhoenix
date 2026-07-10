package com.modmigrator.service;

import com.modmigrator.model.MigrationConfig;
import com.modmigrator.model.MigrationIssue;
import com.modmigrator.model.MigrationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public class MigrationPipeline {

    private final MappingFetcher mappingFetcher;
    private final RemapperService remapperService;
    private final DecompilerService decompilerService;
    private final ApiDiffAnalyzer apiDiffAnalyzer;
    private final ReportGenerator reportGenerator;

    public MigrationPipeline(Path cacheDir) throws IOException {
        this.mappingFetcher = new MappingFetcher(cacheDir);
        this.remapperService = new RemapperService();
        this.decompilerService = new DecompilerService();
        this.apiDiffAnalyzer = new ApiDiffAnalyzer();
        this.reportGenerator = new ReportGenerator();
    }

    public MigrationResult run(MigrationConfig config, Consumer<String> statusCallback) {
        long startTime = System.currentTimeMillis();
        MigrationResult result = new MigrationResult();
        result.setStatus(MigrationResult.Status.FAILED);
        result.setSummary("Migration not yet complete.");

        try {
            String sourceVersion = config.getSourceModInfo().getDetectedMinecraftVersion();
            String targetVersion = config.getTargetMinecraftVersion();
            Path inputJar = config.getSourceModInfo().getJarPath();
            Path outputDir = config.getOutputDirectory();

            Files.createDirectories(outputDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String modId = config.getSourceModInfo().getModId() != null
                ? config.getSourceModInfo().getModId() : "unknown_mod";

            Path workDir = outputDir.resolve(modId + "_migration_" + timestamp);
            Files.createDirectories(workDir);

            // Step 1: Fetch mappings
            Path sourceMappings = null;
            Path targetMappings = null;

            if (sourceVersion != null && !sourceVersion.isBlank()) {
                try {
                    sourceMappings = mappingFetcher.fetchMojangMappings(sourceVersion, statusCallback);
                } catch (IOException e) {
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.WARNING,
                        MigrationIssue.Category.REMAPPING,
                        "Source Mappings Unavailable",
                        "Could not fetch Mojang mappings for " + sourceVersion + ": " + e.getMessage(),
                        "MappingFetcher",
                        "Remapping will be skipped or may be incomplete."
                    ));
                }
            }

            try {
                targetMappings = mappingFetcher.fetchMojangMappings(targetVersion, statusCallback);
            } catch (IOException e) {
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.WARNING,
                    MigrationIssue.Category.REMAPPING,
                    "Target Mappings Unavailable",
                    "Could not fetch Mojang mappings for " + targetVersion + ": " + e.getMessage(),
                    "MappingFetcher",
                    "Remapping will be skipped or may be incomplete."
                ));
            }

            // Step 2: Remap bytecode
            Path remappedJar = workDir.resolve(modId + "-remapped-" + targetVersion + ".jar");

            try {
                remapperService.remapJar(
                    inputJar, remappedJar,
                    sourceMappings, targetMappings,
                    "official", "named",
                    result, statusCallback
                );
            } catch (IOException e) {
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.ERROR,
                    MigrationIssue.Category.REMAPPING,
                    "Remapping Error",
                    e.getMessage(),
                    "RemapperService",
                    "The original JAR will be used for analysis. Manual remapping may be required."
                ));
                remappedJar = inputJar;
            }

            result.setOutputJar(remappedJar);

            // Step 3: API diff analysis
            if (statusCallback != null) statusCallback.accept("Running API compatibility analysis...");
            apiDiffAnalyzer.analyze(remappedJar, sourceVersion, targetVersion, result, statusCallback);

            // Step 4: Decompile (optional)
            if (config.isDecompileSource()) {
                Path sourceDir = workDir.resolve("sources");
                try {
                    decompilerService.decompileJar(remappedJar, sourceDir, statusCallback);
                    result.setOutputSourceDir(sourceDir);
                } catch (Throwable e) {
                    if (statusCallback != null) statusCallback.accept("Decompilation error: " + e.getMessage());
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.WARNING,
                        MigrationIssue.Category.REMAPPING,
                        "Decompilation Failed",
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "DecompilerService",
                        "The remapped JAR was produced but source code could not be generated. Try disabling decompilation for large mods."
                    ));
                }
            }

            // Step 5: Determine overall status FIRST so the report reflects it
            long errors = result.getErrorCount();
            long warnings = result.getWarningCount();

            if (errors == 0) {
                result.setStatus(MigrationResult.Status.SUCCESS);
                result.setSummary(String.format(
                    "Migration completed successfully. %d warnings, %d issues auto-fixed.",
                    warnings, result.getAutoFixedCount()));
            } else if (errors <= 5) {
                result.setStatus(MigrationResult.Status.PARTIAL_SUCCESS);
                result.setSummary(String.format(
                    "Migration completed with %d errors requiring manual fixes. %d warnings.",
                    errors, warnings));
            } else {
                result.setStatus(MigrationResult.Status.NEEDS_MANUAL_WORK);
                result.setSummary(String.format(
                    "Migration requires significant manual work: %d errors, %d warnings.",
                    errors, warnings));
            }

            // Step 6: Generate report (after status is set)
            if (config.isGenerateReport()) {
                try {
                    Path reportPath = workDir.resolve("migration-report.html");
                    reportGenerator.generateHtmlReport(config, result, reportPath);
                    result.setReportPath(reportPath);
                    if (statusCallback != null) statusCallback.accept("Report saved: " + reportPath);
                } catch (IOException e) {
                    if (statusCallback != null) statusCallback.accept("Warning: Could not save report: " + e.getMessage());
                }
            }

        } catch (Throwable e) {
            result.setStatus(MigrationResult.Status.FAILED);
            result.setSummary("Migration pipeline failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.ERROR,
                MigrationIssue.Category.REMAPPING,
                "Pipeline Failure",
                e.getMessage(),
                "MigrationPipeline",
                "Check the logs for more detail."
            ));
        }

        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        return result;
    }
}
