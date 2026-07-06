package com.modmigrator.service;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public class DecompilerService {

    public Path decompileJar(Path jarPath, Path outputDir, Consumer<String> statusCallback) throws IOException {
        Files.createDirectories(outputDir);

        if (statusCallback != null) statusCallback.accept("Decompiling JAR with CFR...");

        List<String> messages = new ArrayList<>();

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                if (sinkType == SinkType.JAVA) {
                    return List.of(SinkClass.DECOMPILED);
                }
                if (sinkType == SinkType.EXCEPTION) {
                    return List.of(SinkClass.EXCEPTION_MESSAGE);
                }
                if (available.contains(SinkClass.STRING)) {
                    return List.of(SinkClass.STRING);
                }
                return List.of(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                    return sinkItem -> {
                        SinkReturns.Decompiled decompiled = (SinkReturns.Decompiled) sinkItem;
                        String packageName = decompiled.getPackageName();
                        String className = decompiled.getClassName();
                        String sourceCode = decompiled.getJava();

                        Path packageDir = outputDir;
                        if (packageName != null && !packageName.isEmpty()) {
                            packageDir = outputDir.resolve(packageName.replace('.', '/'));
                        }

                        try {
                            Files.createDirectories(packageDir);
                            Path sourceFile = packageDir.resolve(className + ".java");
                            Files.writeString(sourceFile, sourceCode);
                        } catch (IOException e) {
                            messages.add("Failed to write: " + className + " - " + e.getMessage());
                        }
                    };
                }
                if (sinkType == SinkType.EXCEPTION) {
                    return sinkItem -> messages.add("CFR Error: " + sinkItem);
                }
                return sinkItem -> {
                    if (statusCallback != null) statusCallback.accept("CFR: " + sinkItem);
                };
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("outputdir", outputDir.toAbsolutePath().toString());
        options.put("caseinsensitivefs", "true");
        options.put("lomem", "false");
        options.put("comments", "true");
        options.put("decodestringswitch", "true");
        options.put("decodeenumswitch", "true");
        options.put("decodelambdas", "true");

        CfrDriver driver = new CfrDriver.Builder()
            .withOutputSink(sinkFactory)
            .withOptions(options)
            .build();

        driver.analyse(List.of(jarPath.toAbsolutePath().toString()));

        if (statusCallback != null) {
            statusCallback.accept("Decompilation complete. Output: " + outputDir.toAbsolutePath());
        }

        if (!messages.isEmpty()) {
            messages.forEach(m -> {
                if (statusCallback != null) statusCallback.accept("  [CFR] " + m);
            });
        }

        return outputDir;
    }
}
