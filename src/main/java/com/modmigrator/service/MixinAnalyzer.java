package com.modmigrator.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modmigrator.model.MigrationIssue;
import com.modmigrator.model.MigrationResult;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Analyses Mixin configs and refmaps inside a migrated mod jar to detect
 * injection/redirect targets that may no longer exist in the target Minecraft
 * version. This catches the most common cause of Mixin-apply crashes after an
 * automatic remapping migration, regardless of mod loader (Fabric/Forge/NeoForge/Quilt).
 */
public class MixinAnalyzer {

    private static final Set<String> INJECTION_ANNOTATIONS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyExpressionValue;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyReceiver;",
        "Lorg/spongepowered/asm/mixin/injection/At;"
    );

    private final Path targetMappings;

    public MixinAnalyzer(Path targetMappings) {
        this.targetMappings = targetMappings;
    }

    private record UnverifiedMixinTarget(
        String configName,
        String refmapName,
        String mixinClass,
        String mixinMethod,
        String targetOwner,
        String targetName,
        String originalDescriptor,
        List<String> candidates,
        String refmapKey,
        String suggestedRefmapValue
    ) {}

    public void analyze(Path jar, MigrationResult result, Consumer<String> statusCallback) throws IOException {
        if (statusCallback != null) statusCallback.accept("Analysing Mixin configs and refmaps...");

        TargetMappings targetMappings = loadTargetMappings();
        if (targetMappings == null) {
            if (statusCallback != null) statusCallback.accept("Mixin target mappings not available; marking unvalidated Minecraft targets as errors.");
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.WARNING,
                MigrationIssue.Category.REMAPPING,
                "Mixin target mappings unavailable",
                "Target mappings were not available, so Mixin injection targets could not be validated against the destination MC version.",
                "MixinAnalyzer",
                "For Fabric/Quilt mods, ensure Fabric intermediary mappings can be fetched for the target version. All unvalidated Minecraft Mixin targets will be reported as errors."
            ));
        } else if (statusCallback != null) {
            statusCallback.accept("Loaded target mappings for Mixin validation.");
        }

        List<UnverifiedMixinTarget> unverifiedTargets = new ArrayList<>();

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            List<String> mixinConfigs = findMixinConfigs(jarFile);
            if (mixinConfigs.isEmpty()) {
                if (statusCallback != null) statusCallback.accept("No Mixin configs found.");
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.INFO,
                    MigrationIssue.Category.REMAPPING,
                    "No Mixin configs found",
                    "No Mixin configuration files were detected in the remapped jar.",
                    jar.toString(),
                    "No action required."
                ));
                return;
            }

            if (statusCallback != null) {
                statusCallback.accept("Found Mixin configs: " + String.join(", ", mixinConfigs));
            }

            int invalidEntries = 0;

            for (String configName : mixinConfigs) {
                JsonObject config = readJson(jarFile, configName);
                if (config == null) {
                    if (statusCallback != null) statusCallback.accept("Could not read Mixin config: " + configName);
                    continue;
                }

                String refmapName = config.has("refmap")
                    ? config.get("refmap").getAsString()
                    : null;

                JsonObject refmap = null;
                if (refmapName != null) {
                    refmap = readJson(jarFile, refmapName);
                    if (statusCallback != null) {
                        statusCallback.accept(refmap != null
                            ? "Loaded refmap " + refmapName + " for " + configName
                            : "Could not load refmap " + refmapName + " for " + configName);
                    }
                }

                // Refmaps can store the actual mappings under root "mappings" or under
                // "data" (namespace-paired). Collect every class-level mapping object we can find.
                List<JsonObject> refmapMappings = extractRefmapMappings(refmap);

                for (JsonObject mappings : refmapMappings) {
                    if (targetMappings != null) {
                        invalidEntries += validateRefmapMappings(mappings, targetMappings, result, configName);
                    }
                }

                if (!refmapMappings.isEmpty() && targetMappings == null) {
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.WARNING,
                        MigrationIssue.Category.REMAPPING,
                        "Mixin refmap present but not validated",
                        String.format("Mixin config '%s' contains a refmap, but no target mappings were available to validate it.", configName),
                        configName,
                        "Verify all refmap entries still resolve to valid methods/fields in the target MC version."
                    ));
                }

                // Also scan the mixin classes themselves to surface the source location of
                // each injection target.
                List<String> mixinClasses = collectMixinClasses(config);
                if (statusCallback != null) {
                    statusCallback.accept(configName + " declares mixins: " + String.join(", ", mixinClasses));
                }
                for (String mixinClass : mixinClasses) {
                    String classPath = mixinClass.replace('.', '/') + ".class";
                    JarEntry entry = jarFile.getJarEntry(classPath);
                    if (entry == null) {
                        if (statusCallback != null) statusCallback.accept("Mixin class not found in jar: " + classPath);
                        continue;
                    }

                    try (InputStream is = jarFile.getInputStream(entry)) {
                        ClassNode classNode = new ClassNode();
                        new ClassReader(is).accept(classNode, 0);
                        scanMixinClass(classNode, configName, refmapName, refmap, result, targetMappings, unverifiedTargets);
                    }
                }
            }

            if (statusCallback != null) {
                if (targetMappings != null) {
                    statusCallback.accept(String.format(
                        "Mixin analysis complete. %d refmap entries could not be verified.", invalidEntries));
                } else {
                    statusCallback.accept("Mixin analysis complete (validation skipped: no target mappings).");
                }
            }

            if (!unverifiedTargets.isEmpty()) {
                writePatchSuggestions(jar.getParent(), unverifiedTargets);
            }
        }

        if (!unverifiedTargets.isEmpty()) {
            applyRefmapFixes(jar, unverifiedTargets, result, statusCallback);
            rewriteMixinCallbacks(jar, unverifiedTargets, result, statusCallback);
        }
    }

    /**
     * Refmap layouts vary by Mixin version/loader. This gathers every object that
     * looks like a class->members mapping from both root "mappings" and "data".
     */
    private List<JsonObject> extractRefmapMappings(JsonObject refmap) {
        List<JsonObject> found = new ArrayList<>();
        if (refmap == null) return found;

        if (refmap.has("mappings") && refmap.get("mappings").isJsonObject()) {
            found.add(refmap.getAsJsonObject("mappings"));
        }

        if (refmap.has("data") && refmap.get("data").isJsonObject()) {
            JsonObject data = refmap.getAsJsonObject("data");
            for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    found.add(entry.getValue().getAsJsonObject());
                }
            }
        }

        return found;
    }

    private TargetMappings loadTargetMappings() {
        if (targetMappings == null || !Files.exists(targetMappings)) {
            return null;
        }

        try {
            MemoryMappingTree tree = new MemoryMappingTree();
            Path tinyFile = findTinyFile(targetMappings);
            MappingReader.read(tinyFile, tree);

            int targetNs = tree.getNamespaceId("intermediary");
            if (targetNs < 0) {
                targetNs = tree.getNamespaceId("srg");
            }
            if (targetNs < 0 && tree.getDstNamespaces().size() >= 1) {
                targetNs = 1; // Fallback: second namespace (official(0) -> intermediary/srg(1))
            }
            if (targetNs < 0) {
                return null;
            }

            Set<String> methods = new HashSet<>();
            Set<String> fields = new HashSet<>();
            Map<String, Set<String>> methodsByName = new HashMap<>();
            Map<String, Set<String>> fieldsByName = new HashMap<>();

            for (MappingTree.ClassMapping cls : tree.getClasses()) {
                String owner = cls.getName(targetNs);
                if (owner == null) continue;
                String ownerSlash = owner.replace('.', '/');

                for (MappingTree.MethodMapping method : cls.getMethods()) {
                    String name = method.getName(targetNs);
                    String desc = method.getDesc(targetNs);
                    if (name == null) continue;
                    methods.add(ownerSlash + "#" + name + (desc != null ? desc : ""));
                    methodsByName.computeIfAbsent(ownerSlash + "#" + name, k -> new HashSet<>()).add(desc != null ? desc : "");
                }

                for (MappingTree.FieldMapping field : cls.getFields()) {
                    String name = field.getName(targetNs);
                    String desc = field.getDesc(targetNs);
                    if (name == null) continue;
                    fields.add(ownerSlash + "#" + name + (desc != null ? desc : ""));
                    fieldsByName.computeIfAbsent(ownerSlash + "#" + name, k -> new HashSet<>()).add(desc != null ? desc : "");
                }
            }

            return new TargetMappings(methods, fields, methodsByName, fieldsByName);
        } catch (Exception e) {
            return null;
        }
    }

    private Path findTinyFile(Path mappingsJarOrFile) throws IOException {
        if (!Files.isDirectory(mappingsJarOrFile) && mappingsJarOrFile.toString().endsWith(".jar")) {
            try (JarFile jar = new JarFile(mappingsJarOrFile.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".tiny") || entry.getName().endsWith(".tiny2")) {
                        Path tmp = Files.createTempFile("mappings-", ".tiny");
                        try (InputStream is = jar.getInputStream(entry)) {
                            Files.copy(is, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        return tmp;
                    }
                }
            }
        }
        return mappingsJarOrFile;
    }

    /**
     * Detects Mixin config files for any loader. Searches common file patterns and
     * reads loader-specific metadata (Fabric/Quilt/Forge) for explicit config lists.
     */
    private List<String> findMixinConfigs(JarFile jar) {
        List<String> configs = new ArrayList<>();
        Set<String> entries = new HashSet<>();
        Enumeration<JarEntry> jarEntries = jar.entries();
        while (jarEntries.hasMoreElements()) {
            entries.add(jarEntries.nextElement().getName());
        }

        // Common Mixin config file patterns used by Fabric, Forge, NeoForge and Quilt.
        for (String name : entries) {
            if (name.endsWith(".mixins.json") || name.equals("mixins.json") || name.endsWith(".refmap.json")) {
                if (name.endsWith(".mixins.json") || name.equals("mixins.json")) {
                    configs.add(name);
                }
            }
        }

        // Fabric / Quilt metadata may list configs explicitly.
        JsonObject fabricMod = readJson(jar, "fabric.mod.json");
        if (fabricMod != null && fabricMod.has("mixins")) {
            JsonElement mixins = fabricMod.get("mixins");
            if (mixins.isJsonArray()) {
                for (JsonElement elem : mixins.getAsJsonArray()) {
                    configs.add(elem.getAsString());
                }
            } else if (mixins.isJsonPrimitive()) {
                configs.add(mixins.getAsString());
            }
        }

        JsonObject quiltMod = readJson(jar, "quilt.mod.json");
        if (quiltMod != null && quiltMod.has("mixin")) {
            JsonElement mixin = quiltMod.get("mixin");
            if (mixin.isJsonArray()) {
                for (JsonElement elem : mixin.getAsJsonArray()) {
                    configs.add(elem.getAsString());
                }
            } else if (mixin.isJsonPrimitive()) {
                configs.add(mixin.getAsString());
            }
        }

        // Forge / NeoForge list their configs in mods.toml under the "mixins" key.
        String forgeMixins = readForgeMixins(jar);
        if (forgeMixins != null && !forgeMixins.isBlank()) {
            for (String name : forgeMixins.split("[ ,;]+")) {
                if (!name.isBlank()) configs.add(name.trim());
            }
        }

        return configs.stream().distinct().toList();
    }

    private String readForgeMixins(JarFile jar) {
        for (String tomlName : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
            JarEntry entry = jar.getJarEntry(tomlName);
            if (entry == null) continue;
            try (InputStream is = jar.getInputStream(entry)) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "(?mi)^\\s*mixins\\s*=\\s*\"([^\"]+)\"");
                java.util.regex.Matcher m = p.matcher(content);
                if (m.find()) return m.group(1);
            } catch (IOException ignored) {}
        }
        return null;
    }

    private JsonObject readJson(JarFile jar, String entryName) {
        JarEntry entry = jar.getJarEntry(entryName);
        if (entry == null) return null;
        try (InputStream is = jar.getInputStream(entry)) {
            return JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> collectMixinClasses(JsonObject config) {
        List<String> classes = new ArrayList<>();
        String pkg = config.has("package") ? config.get("package").getAsString() : "";
        if (!pkg.isEmpty() && !pkg.endsWith(".")) pkg += ".";

        for (String key : new String[]{"mixins", "client", "server"}) {
            if (!config.has(key)) continue;
            for (JsonElement elem : config.getAsJsonArray(key)) {
                String name = elem.getAsString();
                classes.add(pkg + name);
            }
        }
        return classes;
    }

    private int validateRefmapMappings(JsonObject mappings, TargetMappings target,
                                       MigrationResult result, String configName) {
        int invalid = 0;
        for (Map.Entry<String, JsonElement> classEntry : mappings.entrySet()) {
            String mixinClass = classEntry.getKey();
            JsonObject members;
            try {
                members = classEntry.getValue().getAsJsonObject();
            } catch (IllegalStateException e) {
                continue;
            }

            for (Map.Entry<String, JsonElement> memberEntry : members.entrySet()) {
                String sourceName = memberEntry.getKey();
                String targetDesc = memberEntry.getValue().getAsString();

                ParsedTarget parsed = parseTargetDescriptor(targetDesc);
                if (parsed == null || !parsed.owner().startsWith("net/minecraft/")) continue;

                String ownerSlash = parsed.owner().replace('.', '/');
                String fullKey = ownerSlash + "#" + parsed.name() + parsed.desc();

                if (target.methods.contains(fullKey)) continue;

                String nameKey = ownerSlash + "#" + parsed.name();
                boolean nameExists = target.methodsByName.containsKey(nameKey);
                invalid++;
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.ERROR,
                    MigrationIssue.Category.CHANGED_SIGNATURE,
                    nameExists
                        ? "Mixin refmap target signature changed in target MC version"
                        : "Mixin refmap target not found in target MC version",
                    String.format(
                        "Refmap entry '%s.%s' resolves to '%s#%s%s', which %s in the target mappings.",
                        mixinClass, sourceName, parsed.owner(), parsed.name(), parsed.desc(),
                        nameExists ? "exists with a different signature" : "does not exist"),
                    configName + " -> " + mixinClass,
                    "This Mixin injection/redirect will fail at runtime. Update the Mixin target for the new Minecraft version."
                ));
            }
        }
        return invalid;
    }

    private void scanMixinClass(ClassNode classNode, String configName, String refmapName, JsonObject refmap,
                                MigrationResult result, TargetMappings targetMappings,
                                List<UnverifiedMixinTarget> unverifiedTargets) {
        String mixinClassSlash = classNode.name;
        String annotatedTarget = getMixinTarget(classNode);

        for (MethodNode method : classNode.methods) {
            if (method.visibleAnnotations == null) continue;
            for (AnnotationNode ann : method.visibleAnnotations) {
                if (!INJECTION_ANNOTATIONS.contains(ann.desc)) continue;

                List<String> targets = parseAnnotationMethodValue(ann);
                if (targets.isEmpty()) continue;

                for (String target : targets) {
                    // Resolve the concrete target descriptor from the refmap.
                    String targetDesc = null;
                    if (refmap != null) {
                        targetDesc = resolveRefmap(refmap, mixinClassSlash, target);
                    }

                    // Determine the owner of the target method/field. Prefer the @Mixin
                    // annotation value, but fall back to the refmap descriptor owner if the
                    // annotation was stripped or the target is specified indirectly.
                    String targetOwner = annotatedTarget;
                    ParsedTarget parsed = null;
                    if (targetDesc != null) {
                        parsed = parseTargetDescriptor(targetDesc);
                        if (parsed != null) {
                            if (targetOwner == null) {
                                targetOwner = parsed.owner().replace('.', '/');
                            }
                        }
                    }

                    if (targetOwner == null || !targetOwner.replace('.', '/').startsWith("net/minecraft/")) {
                        continue;
                    }

                    if (targetDesc == null) {
                        // No refmap entry for this target -> flag as high risk.
                        result.addIssue(new MigrationIssue(
                            MigrationIssue.Severity.ERROR,
                            MigrationIssue.Category.CHANGED_SIGNATURE,
                            "Mixin injection target not verified",
                            String.format("Mixin '%s#%s' targets '%s#%s' but its refmap entry could not be resolved against the destination MC version.",
                                classNode.name, method.name, targetOwner, target),
                            configName + " -> " + classNode.name + "#" + method.name,
                            "Verify this target still exists with the expected signature in the target MC version."
                        ));
                        continue;
                    }

                    boolean verified = false;
                    String ownerSlash = parsed.owner().replace('.', '/');
                    String nameKey = ownerSlash + "#" + parsed.name();
                    Set<String> sameNameDescs = targetMappings == null
                        ? Collections.emptySet()
                        : targetMappings.methodsByName.getOrDefault(nameKey, Collections.emptySet());
                    List<String> candidates = targetMappings == null
                        ? Collections.emptyList()
                        : findCandidateMethods(targetMappings, ownerSlash, parsed.name());
                    if (parsed != null && targetMappings != null) {
                        verified = targetMappings.methods.contains(
                            ownerSlash + "#" + parsed.name() + parsed.desc());
                    }

                    if (verified) {
                        result.addIssue(new MigrationIssue(
                            MigrationIssue.Severity.INFO,
                            MigrationIssue.Category.REMAPPING,
                            "Mixin injection target verified",
                            String.format("Mixin '%s#%s' targets '%s#%s' (resolved to '%s').",
                                classNode.name, method.name, targetOwner, target, targetDesc),
                            configName + " -> " + classNode.name + "#" + method.name,
                            "Target was found in the target version's mappings."
                        ));
                    } else {
                        StringBuilder suggestion = new StringBuilder();
                        if (targetMappings == null) {
                            suggestion.append("Target mappings were unavailable. If the target method/field changed between versions, this Mixin will crash at runtime.");
                        } else if (!candidates.isEmpty()) {
                            suggestion.append("The target could not be verified. Candidate methods on the destination class (same name first):\n");
                            for (String candidate : candidates) {
                                suggestion.append("- ").append(candidate).append("\n");
                            }
                            suggestion.append("Choose the descriptor that matches the new Minecraft method and update the Mixin/Refmap target.");
                        } else {
                            suggestion.append("The resolved target does not exist with the expected signature in the destination MC version. Update the Mixin target.");
                        }

                        String suggestedRefmapValue = null;
                        if (sameNameDescs.size() == 1) {
                            String desc = sameNameDescs.iterator().next();
                            suggestedRefmapValue = "L" + ownerSlash + ";" + parsed.name() + desc;
                        }

                        unverifiedTargets.add(new UnverifiedMixinTarget(
                            configName,
                            refmapName,
                            classNode.name,
                            method.name,
                            targetOwner,
                            target,
                            targetDesc,
                            candidates,
                            findRefmapKey(refmap, mixinClassSlash, target),
                            suggestedRefmapValue
                        ));
                        result.addIssue(new MigrationIssue(
                            MigrationIssue.Severity.ERROR,
                            MigrationIssue.Category.CHANGED_SIGNATURE,
                            "Mixin injection target cannot be verified",
                            String.format(
                                "Mixin '%s#%s' targets '%s#%s' (resolved to '%s'), but this target could not be verified in the destination MC version's mappings.",
                                classNode.name, method.name, targetOwner, target, targetDesc),
                            configName + " -> " + classNode.name + "#" + method.name,
                            suggestion.toString()
                        ));
                    }
                }
            }
        }
    }

    /**
     * Parses a Mixin target descriptor such as
     * {@code Lnet/minecraft/class_2358;method_24855(Lnet/minecraft/class_1936;I)Lnet/minecraft/class_2680;}
     * into owner, name and descriptor.
     */
    private ParsedTarget parseTargetDescriptor(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L")) return null;
        int semi = descriptor.indexOf(';');
        if (semi < 0) return null;
        String owner = descriptor.substring(1, semi).replace('/', '.');
        String nameAndDesc = descriptor.substring(semi + 1);
        int paren = nameAndDesc.indexOf('(');
        if (paren < 0) {
            // Field reference
            return new ParsedTarget(owner, nameAndDesc, "");
        }
        String name = nameAndDesc.substring(0, paren);
        String desc = nameAndDesc.substring(paren);
        return new ParsedTarget(owner, name, desc);
    }

    private record ParsedTarget(String owner, String name, String desc) {}

    private String getMixinTarget(ClassNode classNode) {
        if (classNode.visibleAnnotations == null) return null;
        for (AnnotationNode ann : classNode.visibleAnnotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(ann.desc)) continue;
            if (ann.values == null) continue;
            for (int i = 0; i < ann.values.size() - 1; i += 2) {
                String key = (String) ann.values.get(i);
                Object value = ann.values.get(i + 1);
                if ("value".equals(key) && value instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Type type) {
                        return type.getClassName();
                    }
                }
                if ("targets".equals(key) && value instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof String s) return s.replace('/', '.');
                }
            }
        }
        return null;
    }

    private List<String> parseAnnotationMethodValue(AnnotationNode ann) {
        List<String> values = new ArrayList<>();
        if (ann.values == null) return values;
        for (int i = 0; i < ann.values.size() - 1; i += 2) {
            String key = (String) ann.values.get(i);
            if (!"method".equals(key)) continue;
            Object value = ann.values.get(i + 1);
            if (value instanceof String s) {
                values.add(s);
            } else if (value instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof String s) values.add(s);
                }
            }
        }
        return values;
    }

    private String resolveRefmap(JsonObject refmap, String mixinClassSlash, String target) {
        JsonObject mappings = refmap.has("mappings") ? refmap.getAsJsonObject("mappings") : refmap;
        JsonObject classMappings = mappings.getAsJsonObject(mixinClassSlash);
        if (classMappings == null) return null;

        for (Map.Entry<String, JsonElement> e : classMappings.entrySet()) {
            if (e.getKey().startsWith(target)) {
                return e.getValue().getAsString();
            }
        }
        return null;
    }

    private String findRefmapKey(JsonObject refmap, String mixinClassSlash, String target) {
        if (refmap == null || mixinClassSlash == null || target == null) return null;
        JsonObject mappings = refmap.has("mappings") ? refmap.getAsJsonObject("mappings") : refmap;
        JsonObject classMappings = mappings.getAsJsonObject(mixinClassSlash);
        if (classMappings == null) return null;
        for (Map.Entry<String, JsonElement> e : classMappings.entrySet()) {
            if (e.getKey().startsWith(target)) {
                return e.getKey();
            }
        }
        return null;
    }

    private record TargetMappings(
        Set<String> methods,
        Set<String> fields,
        Map<String, Set<String>> methodsByName,
        Map<String, Set<String>> fieldsByName
    ) {}

    /**
     * Writes a human-readable patch suggestion file listing every unverified
     * Mixin target and candidate replacements from the destination mappings.
     */
    private void writePatchSuggestions(Path outputDir, List<UnverifiedMixinTarget> targets) throws IOException {
        if (outputDir == null) return;
        Files.createDirectories(outputDir);
        Path patchFile = outputDir.resolve("mixin-patch-suggestions.md");
        try (var writer = Files.newBufferedWriter(patchFile, StandardCharsets.UTF_8)) {
            writer.write("# Mixin Patch Suggestions\n\n");
            writer.write("This file lists Mixin injection targets that could not be verified against the destination Minecraft version's mappings, ");
            writer.write("along with candidate replacement descriptors.\n\n");
            writer.write("**How to use this:** compare the original descriptor with each candidate. Choose the candidate that matches the new method in the target version. ");
            writer.write("If the parameter types changed, the Mixin callback method in the source code may also need updating.\n\n");
            int idx = 1;
            for (UnverifiedMixinTarget t : targets) {
                writer.write("## " + idx + ". `" + t.mixinClass.replace('/', '.') + "#" + t.mixinMethod + "`\n\n");
                writer.write("- **Mixin config:** `" + t.configName + "`\n");
                writer.write("- **Mixin method:** `" + t.mixinClass.replace('/', '.') + "." + t.mixinMethod + "`\n");
                writer.write("- **Target class:** `" + t.targetOwner.replace('/', '.') + "`\n");
                writer.write("- **Target name:** `" + t.targetName + "`\n");
                writer.write("- **Original descriptor:** `" + t.originalDescriptor + "`\n\n");
                if (t.candidates.isEmpty()) {
                    writer.write("**No candidate methods were found on the target class in the destination mappings.**\n\n");
                } else {
                    writer.write("**Candidate replacement descriptors** (same method name first):\n\n");
                    for (String candidate : t.candidates) {
                        writer.write("- `" + candidate + "`\n");
                    }
                    writer.write("\n");
                }
                writer.write("---\n\n");
                idx++;
            }
        }
    }

    /**
     * Collects candidate method descriptors on the given owner class.
     * Methods with the same name are returned first, followed by other methods
     * on the class, so the user can pick the most likely replacement.
     */
    private List<String> findCandidateMethods(TargetMappings targetMappings, String ownerSlash, String originalName) {
        if (targetMappings == null || ownerSlash == null) return Collections.emptyList();
        String prefix = ownerSlash + "#";
        List<String> sameName = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String method : targetMappings.methods) {
            if (!method.startsWith(prefix)) continue;
            int nameEnd = method.indexOf('(', prefix.length());
            if (nameEnd < 0) continue;
            String name = method.substring(prefix.length(), nameEnd);
            if (name.equals(originalName)) sameName.add(method);
            else others.add(method);
        }
        List<String> result = new ArrayList<>(sameName);
        Collections.sort(others);
        result.addAll(others);
        return result.stream().limit(15).toList();
    }

    /**
     * Applies conservative refmap-only fixes to the output jar. A fix is only
     * applied when the target method name exists with exactly one alternative
     * descriptor in the destination mappings. The Mixin callback bytecode is
     * NOT modified, so a manual source fix may still be required if parameter
     * types changed semantically.
     */
    private void applyRefmapFixes(Path jar, List<UnverifiedMixinTarget> targets,
                                  MigrationResult result, Consumer<String> statusCallback) throws IOException {
        Map<String, List<UnverifiedMixinTarget>> byRefmap = new HashMap<>();
        for (UnverifiedMixinTarget t : targets) {
            if (t.refmapName != null && t.refmapKey != null && t.suggestedRefmapValue != null) {
                byRefmap.computeIfAbsent(t.refmapName, k -> new ArrayList<>()).add(t);
            }
        }
        if (byRefmap.isEmpty()) return;

        Path tempJar = jar.resolveSibling(jar.getFileName().toString() + ".tmp");
        int applied = 0;
        try (JarFile original = new JarFile(jar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {

            Set<String> replaced = new HashSet<>();
            Gson gson = new Gson();

            for (Map.Entry<String, List<UnverifiedMixinTarget>> entry : byRefmap.entrySet()) {
                String refmapName = entry.getKey();
                JarEntry refmapEntry = original.getJarEntry(refmapName);
                if (refmapEntry == null) continue;

                JsonObject refmap = JsonParser.parseReader(
                    new InputStreamReader(original.getInputStream(refmapEntry), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject mappings = refmap.has("mappings") ? refmap.getAsJsonObject("mappings") : refmap;

                for (UnverifiedMixinTarget t : entry.getValue()) {
                    JsonObject classMappings = mappings.getAsJsonObject(t.mixinClass);
                    if (classMappings == null) continue;
                    if (classMappings.has(t.refmapKey)) {
                        classMappings.addProperty(t.refmapKey, t.suggestedRefmapValue);
                        applied++;
                        result.addIssue(new MigrationIssue(
                            MigrationIssue.Severity.WARNING,
                            MigrationIssue.Category.REMAPPING,
                            "Mixin refmap auto-updated",
                            String.format("Refmap entry '%s.%s' updated from '%s' to '%s' for Mixin '%s#%s'.",
                                t.mixinClass, t.refmapKey, t.originalDescriptor, t.suggestedRefmapValue, t.mixinClass, t.mixinMethod),
                            t.configName + " -> " + t.mixinClass + "#" + t.mixinMethod,
                            "The Mixin callback bytecode was not changed. If parameter types changed semantically, the mod may still crash and the callback source may need manual updating."
                        ));
                    }
                }

                JarEntry newEntry = new JarEntry(refmapName);
                jos.putNextEntry(newEntry);
                jos.write(gson.toJson(refmap).getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
                replaced.add(refmapName);
            }

            byte[] buffer = new byte[8192];
            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (replaced.contains(e.getName())) continue;
                jos.putNextEntry(e);
                try (InputStream is = original.getInputStream(e)) {
                    int read;
                    while ((read = is.read(buffer)) != -1) jos.write(buffer, 0, read);
                }
                jos.closeEntry();
            }
        }

        Files.move(tempJar, jar, StandardCopyOption.REPLACE_EXISTING);
        if (statusCallback != null) statusCallback.accept(
            String.format("Applied %d Mixin refmap update(s) to %s.", applied, jar.getFileName()));
    }

    /**
     * Rewrites Mixin callback method bytecode so the method descriptor matches the
     * new target signature. This is an aggressive fix: it changes parameter types in
     * the callback method, which may still fail at runtime if the method body calls
     * methods that don't exist on the new parameter type.
     */
    private void rewriteMixinCallbacks(Path jar, List<UnverifiedMixinTarget> targets,
                                       MigrationResult result, Consumer<String> statusCallback) throws IOException {
        Map<String, List<UnverifiedMixinTarget>> byClass = new HashMap<>();
        for (UnverifiedMixinTarget t : targets) {
            if (t.suggestedRefmapValue == null) continue;
            byClass.computeIfAbsent(t.mixinClass + ".class", k -> new ArrayList<>()).add(t);
        }
        if (byClass.isEmpty()) return;

        Path tempJar = jar.resolveSibling(jar.getFileName().toString() + ".tmp");
        int rewritten = 0;
        try (JarFile original = new JarFile(jar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tempJar))) {

            Set<String> rewrittenEntries = new HashSet<>();
            byte[] buffer = new byte[8192];

            for (Map.Entry<String, List<UnverifiedMixinTarget>> entry : byClass.entrySet()) {
                String classEntryName = entry.getKey();
                JarEntry classEntry = original.getJarEntry(classEntryName);
                if (classEntry == null) continue;

                byte[] classBytes;
                try (InputStream is = original.getInputStream(classEntry)) {
                    classBytes = is.readAllBytes();
                }
                byte[] newBytes = rewriteMixinCallbackBytecode(classBytes, entry.getValue());
                if (newBytes == null) continue;

                JarEntry newEntry = new JarEntry(classEntryName);
                jos.putNextEntry(newEntry);
                jos.write(newBytes);
                jos.closeEntry();
                rewrittenEntries.add(classEntryName);
                rewritten++;

                for (UnverifiedMixinTarget t : entry.getValue()) {
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.WARNING,
                        MigrationIssue.Category.REMAPPING,
                        "Mixin callback bytecode rewritten",
                        String.format("Method '%s#%s' was rewritten to match the new target signature for '%s#%s'.",
                            t.mixinClass, t.mixinMethod, t.targetOwner, t.targetName),
                        t.configName + " -> " + t.mixinClass + "#" + t.mixinMethod,
                        "Parameter types in the callback method were changed. If the method body calls methods not present on the new parameter type, the mod will still crash."
                    ));
                }
            }

            Enumeration<JarEntry> entries = original.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (rewrittenEntries.contains(e.getName())) continue;
                jos.putNextEntry(e);
                try (InputStream is = original.getInputStream(e)) {
                    int read;
                    while ((read = is.read(buffer)) != -1) jos.write(buffer, 0, read);
                }
                jos.closeEntry();
            }
        }

        Files.move(tempJar, jar, StandardCopyOption.REPLACE_EXISTING);
        if (statusCallback != null) statusCallback.accept(
            String.format("Rewrote %d Mixin callback method(s) in %s.", rewritten, jar.getFileName()));
    }

    private byte[] rewriteMixinCallbackBytecode(byte[] classBytes, List<UnverifiedMixinTarget> targets) {
        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        boolean changed = false;

        for (UnverifiedMixinTarget t : targets) {
            MethodNode method = null;
            for (MethodNode m : classNode.methods) {
                if (m.name.equals(t.mixinMethod)) {
                    method = m;
                    break;
                }
            }
            if (method == null) continue;

            Type oldTargetType = parseTargetMethodType(t.originalDescriptor);
            Type newTargetType = parseTargetMethodType(t.suggestedRefmapValue);
            if (oldTargetType == null || newTargetType == null) continue;

            Type[] oldTargetArgs = oldTargetType.getArgumentTypes();
            Type[] newTargetArgs = newTargetType.getArgumentTypes();
            if (oldTargetArgs.length != newTargetArgs.length) continue;

            Type[] oldCallbackArgs = Type.getArgumentTypes(method.desc);
            if (oldCallbackArgs.length < oldTargetArgs.length + 1) continue;

            Type[] newCallbackArgs = oldCallbackArgs.clone();
            for (int i = 0; i < newTargetArgs.length; i++) {
                newCallbackArgs[i] = newTargetArgs[i];
            }
            String newCallbackDesc = Type.getMethodDescriptor(Type.getReturnType(method.desc), newCallbackArgs);

            Map<String, String> typeMap = new HashMap<>();
            for (int i = 0; i < oldTargetArgs.length; i++) {
                if (!oldTargetArgs[i].equals(newTargetArgs[i])) {
                    typeMap.put(oldTargetArgs[i].getInternalName(), newTargetArgs[i].getInternalName());
                }
            }
            if (typeMap.isEmpty()) continue;

            String[] exceptions = method.exceptions == null ? null : method.exceptions.toArray(new String[0]);
            MethodNode newMethod = new MethodNode(method.access, method.name, newCallbackDesc, null, exceptions);
            MethodRemapper remapper = new MethodRemapper(newMethod, new SimpleRemapper(typeMap));
            method.accept(remapper);

            classNode.methods.set(classNode.methods.indexOf(method), newMethod);
            changed = true;
        }

        if (!changed) return null;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private Type parseTargetMethodType(String refmapDescriptor) {
        int paren = refmapDescriptor.indexOf('(');
        if (paren < 0) return null;
        return Type.getMethodType(refmapDescriptor.substring(paren));
    }
}
