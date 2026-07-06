package com.modmigrator.service;

import com.modmigrator.model.MigrationIssue;
import com.modmigrator.model.MigrationResult;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class RemapperService {

    public Path remapJar(
        Path inputJar,
        Path outputJar,
        Path sourceMappings,
        Path targetMappings,
        String sourceNamespace,
        String targetNamespace,
        MigrationResult result,
        Consumer<String> statusCallback
    ) throws IOException {

        if (statusCallback != null) statusCallback.accept("Initialising bytecode remapper...");

        Files.deleteIfExists(outputJar);
        Files.createDirectories(outputJar.getParent());

        try {
            IMappingProvider mappingProvider = buildMappingProvider(
                sourceMappings, targetMappings, sourceNamespace, targetNamespace, statusCallback);

            if (statusCallback != null) statusCallback.accept("Remapping bytecode...");

            TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(mappingProvider)
                .ignoreConflicts(true)
                .resolveMissing(true)
                .build();

            try {
                net.fabricmc.tinyremapper.OutputConsumerPath outputConsumer =
                    new net.fabricmc.tinyremapper.OutputConsumerPath.Builder(outputJar).build();
                try {
                    outputConsumer.addNonClassFiles(inputJar);
                    remapper.readInputs(inputJar);
                    remapper.apply(outputConsumer);
                } finally {
                    outputConsumer.close();
                }
            } finally {
                remapper.finish();
            }

            if (statusCallback != null) statusCallback.accept("Bytecode remapping complete.");
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.INFO,
                MigrationIssue.Category.REMAPPING,
                "Bytecode Remapping Complete",
                "Classes, methods, and fields have been remapped from source to target Minecraft version mappings.",
                "All classes",
                "No action required."
            ));

        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.ERROR,
                MigrationIssue.Category.REMAPPING,
                "Remapping Failed",
                "Error during bytecode remapping: " + e.getMessage(),
                "RemapperService",
                "Check that the source and target mappings are correct and that the JAR is a valid mod."
            ));
            throw new IOException("Remapping failed: " + e.getMessage(), e);
        }

        return outputJar;
    }

    private IMappingProvider buildMappingProvider(
        Path sourceMappings, Path targetMappings,
        String sourceNamespace, String targetNamespace,
        Consumer<String> statusCallback
    ) throws IOException {

        if (sourceMappings == null && targetMappings == null) {
            return sink -> {};
        }

        if (statusCallback != null) statusCallback.accept("Loading source mappings...");

        if (sourceMappings != null && sourceMappings.toString().endsWith(".txt")) {
            return buildFromProguardMappings(sourceMappings, targetMappings, statusCallback);
        }

        if (sourceMappings != null && (sourceMappings.toString().endsWith(".jar")
            || sourceMappings.toString().endsWith(".tiny"))) {
            return buildFromTinyMappings(sourceMappings, targetMappings,
                sourceNamespace, targetNamespace, statusCallback);
        }

        return sink -> {};
    }

    private IMappingProvider buildFromProguardMappings(
        Path sourceMappings, Path targetMappings, Consumer<String> statusCallback
    ) throws IOException {
        if (statusCallback != null) statusCallback.accept("Parsing Proguard/Mojang mappings...");

        MemoryMappingTree sourceTree = new MemoryMappingTree();
        MappingReader.read(sourceMappings, sourceTree);

        if (targetMappings == null) {
            return sink -> {};
        }

        if (statusCallback != null) statusCallback.accept("Parsing target mappings...");
        MemoryMappingTree targetTree = new MemoryMappingTree();
        MappingReader.read(targetMappings, targetTree);

        return sink -> {
            for (MappingTree.ClassMapping cls : targetTree.getClasses()) {
                String srcName = cls.getSrcName();
                String dstName = cls.getDstName(0);
                if (dstName != null && !srcName.equals(dstName)) {
                    sink.acceptClass(srcName, dstName);
                }
                for (MappingTree.MethodMapping method : cls.getMethods()) {
                    String mSrc = method.getSrcName();
                    String mDst = method.getDstName(0);
                    if (mDst != null && !mSrc.equals(mDst)) {
                        sink.acceptMethod(
                            new IMappingProvider.Member(srcName, mSrc, method.getSrcDesc()),
                            mDst
                        );
                    }
                }
                for (MappingTree.FieldMapping field : cls.getFields()) {
                    String fSrc = field.getSrcName();
                    String fDst = field.getDstName(0);
                    if (fDst != null && !fSrc.equals(fDst)) {
                        sink.acceptField(
                            new IMappingProvider.Member(srcName, fSrc, field.getSrcDesc()),
                            fDst
                        );
                    }
                }
            }
        };
    }

    private IMappingProvider buildFromTinyMappings(
        Path sourceMappings, Path targetMappings,
        String sourceNamespace, String targetNamespace,
        Consumer<String> statusCallback
    ) throws IOException {
        if (statusCallback != null) statusCallback.accept("Parsing Tiny mappings...");
        return TinyUtils.createTinyMappingProvider(
            targetMappings, sourceNamespace, targetNamespace);
    }
}
