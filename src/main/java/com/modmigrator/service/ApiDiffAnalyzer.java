package com.modmigrator.service;

import com.modmigrator.model.MigrationIssue;
import com.modmigrator.model.MigrationResult;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ApiDiffAnalyzer {

    private static final Map<String, ApiChange> KNOWN_API_CHANGES = buildKnownApiChanges();

    public record ApiChange(
        String oldOwner,
        String oldName,
        String oldDesc,
        String newOwner,
        String newName,
        String newDesc,
        String sourceVersion,
        String targetVersion,
        String note
    ) {}

    public void analyze(Path remappedJar, String sourceVersion, String targetVersion,
                        Path targetMappings, MigrationResult result, Consumer<String> statusCallback) throws IOException {

        if (statusCallback != null) statusCallback.accept("Analysing API compatibility...");

        TargetMappings mappings = loadTargetMappings(targetMappings);

        try (JarFile jar = new JarFile(remappedJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            int classesAnalyzed = 0;

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;

                try (InputStream is = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is);
                    ClassNode classNode = new ClassNode();
                    reader.accept(classNode, 0);

                    analyzeClass(classNode, sourceVersion, targetVersion, mappings, result);
                    classesAnalyzed++;
                } catch (Exception e) {
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.WARNING,
                        MigrationIssue.Category.REMAPPING,
                        "Class Analysis Failed",
                        "Could not analyse class: " + entry.getName() + " — " + e.getMessage(),
                        entry.getName(),
                        "This class may require manual inspection."
                    ));
                }
            }

            if (statusCallback != null) {
                statusCallback.accept(String.format("Analysed %d classes.", classesAnalyzed));
            }
        }
    }

    private void analyzeClass(ClassNode classNode, String sourceVersion, String targetVersion,
                               TargetMappings mappings, MigrationResult result) {

        for (MethodNode method : classNode.methods) {
            if (method.instructions == null) continue;

            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode methodInsn) {
                    checkMethodCall(methodInsn, classNode.name, method.name,
                        sourceVersion, targetVersion, mappings, result);
                }
                if (insn instanceof FieldInsnNode fieldInsn) {
                    checkFieldAccess(fieldInsn, classNode.name, method.name,
                        sourceVersion, targetVersion, result);
                }
            }

            checkMethodAnnotations(method, classNode.name, result);
        }

        checkClassHierarchy(classNode, sourceVersion, targetVersion, result);
    }

    private void checkMethodCall(MethodInsnNode insn, String ownerClass, String callerMethod,
                                  String sourceVersion, String targetVersion,
                                  TargetMappings mappings, MigrationResult result) {
        String key = insn.owner + "#" + insn.name + insn.desc;
        ApiChange change = KNOWN_API_CHANGES.get(key);

        if (change != null) {
            boolean sourceMatches = isVersionInRange(sourceVersion, change.sourceVersion());
            boolean targetMatches = isVersionInRange(targetVersion, change.targetVersion());

            if (sourceMatches && targetMatches) {
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.ERROR,
                    MigrationIssue.Category.CHANGED_SIGNATURE,
                    "API Change Detected: " + insn.name,
                    String.format("Method '%s' in '%s' has changed. %s",
                        insn.name, insn.owner, change.note()),
                    ownerClass + "#" + callerMethod,
                    change.newOwner() != null
                        ? "Use " + change.newOwner() + "#" + change.newName() + " instead."
                        : "No direct replacement — manual rewrite required."
                ));
            }
        }

        if (mappings != null && insn.owner.startsWith("net/minecraft/")
            && !"<init>".equals(insn.name) && !"<clinit>".equals(insn.name)
            && !"values".equals(insn.name) && !"valueOf".equals(insn.name)) {
            boolean exactMatch = mappings.methods.contains(key);
            boolean inheritedMatch = false;
            for (String method : mappings.methods) {
                int hash = method.indexOf('#');
                if (hash < 0) continue;
                int paren = method.indexOf('(', hash);
                if (paren < 0) continue;
                String name = method.substring(hash + 1, paren);
                String desc = method.substring(paren);
                if (insn.name.equals(name) && insn.desc.equals(desc)) {
                    inheritedMatch = true;
                    break;
                }
            }
            if (!exactMatch && !inheritedMatch) {
                List<String> candidates = findCandidateMethods(mappings, insn.owner, insn.name);
                StringBuilder suggestion = new StringBuilder("This method call could not be verified in the destination MC version's mappings.");
                if (!candidates.isEmpty()) {
                    suggestion.append(" Candidate signatures on the destination class:\n");
                    for (String c : candidates) {
                        suggestion.append("- ").append(c).append("\n");
                    }
                }
                suggestion.append("The method may have been removed or its signature may have changed.");
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.ERROR,
                    MigrationIssue.Category.CHANGED_SIGNATURE,
                    "Invalid Minecraft API method call",
                    String.format("Method '%s#%s%s' in '%s#%s' does not exist with this signature in the target MC version.",
                        insn.owner, insn.name, insn.desc, ownerClass, callerMethod),
                    ownerClass + "#" + callerMethod,
                    suggestion.toString()
                ));
            }
        }

        checkCommonApiPatterns(insn, ownerClass, callerMethod, sourceVersion, targetVersion, result);
    }

    private void checkCommonApiPatterns(MethodInsnNode insn, String ownerClass, String callerMethod,
                                         String sourceVersion, String targetVersion, MigrationResult result) {

        if (insn.owner.startsWith("net/minecraft/world/biome/Biome") &&
            insn.name.equals("func_") || insn.name.startsWith("m_")) {
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.WARNING,
                MigrationIssue.Category.REMAPPING,
                "Unmapped Minecraft Method Reference",
                String.format("Method '%s' in '%s' uses an obfuscated/unmapped name and may not function correctly.",
                    insn.name, insn.owner),
                ownerClass + "#" + callerMethod,
                "Verify that remapping was successful for this reference."
            ));
        }

        if (targetVersion != null && isNewerThan(targetVersion, "1.18") &&
            insn.owner.contains("IWorld") && sourceVersion != null && !isNewerThan(sourceVersion, "1.18")) {
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.ERROR,
                MigrationIssue.Category.REMOVED_API,
                "Removed Interface: IWorld",
                "IWorld was removed in 1.18 and replaced by LevelAccessor/Level.",
                ownerClass + "#" + callerMethod,
                "Replace IWorld with net.minecraft.world.level.LevelAccessor or Level."
            ));
        }

        if (targetVersion != null && isNewerThan(targetVersion, "1.17") &&
            insn.owner.contains("World") && !insn.owner.contains("Level") &&
            sourceVersion != null && !isNewerThan(sourceVersion, "1.17")) {
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.WARNING,
                MigrationIssue.Category.CHANGED_SIGNATURE,
                "World -> Level Rename",
                String.format("'%s' may have been renamed. World classes became Level classes in 1.17+.", insn.owner),
                ownerClass + "#" + callerMethod,
                "Check if this class was remapped correctly to the Level equivalent."
            ));
        }
    }

    private void checkFieldAccess(FieldInsnNode insn, String ownerClass, String callerMethod,
                                   String sourceVersion, String targetVersion, MigrationResult result) {
        if (insn.name.startsWith("field_") || insn.name.startsWith("f_")) {
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.WARNING,
                MigrationIssue.Category.REMAPPING,
                "Unmapped Field Reference",
                String.format("Field '%s' in '%s' may be unmapped.", insn.name, insn.owner),
                ownerClass + "#" + callerMethod,
                "Verify that remapping succeeded for this field."
            ));
        }
    }

    private void checkMethodAnnotations(MethodNode method, String ownerClass, MigrationResult result) {
        if (method.visibleAnnotations == null) return;
        for (AnnotationNode ann : method.visibleAnnotations) {
            if (ann.desc != null && ann.desc.contains("Deprecated")) {
                result.addIssue(new MigrationIssue(
                    MigrationIssue.Severity.WARNING,
                    MigrationIssue.Category.DEPRECATED_API,
                    "Deprecated Method Override",
                    String.format("Method '%s' in '%s' overrides a deprecated method.", method.name, ownerClass),
                    ownerClass + "#" + method.name,
                    "Check if a newer API exists for this functionality."
                ));
            }
            if (ann.desc != null && ann.desc.contains("Override")) {
                checkOverrideValidity(method, ownerClass, result);
            }
        }
    }

    private void checkOverrideValidity(MethodNode method, String ownerClass, MigrationResult result) {
        Set<String> knownChangedOverrides = Set.of(
            "tick", "onLoad", "onUnload", "onPlace", "onRemove",
            "getDrops", "use", "attack", "entityInside"
        );
        if (knownChangedOverrides.contains(method.name)) {
            result.addIssue(new MigrationIssue(
                MigrationIssue.Severity.INFO,
                MigrationIssue.Category.CHANGED_SIGNATURE,
                "Possible Override Signature Change",
                String.format("Method '%s' in '%s' has a common signature that changed across MC versions.",
                    method.name, ownerClass),
                ownerClass + "#" + method.name,
                "Verify the method signature matches the target version's API."
            ));
        }
    }

    private void checkClassHierarchy(ClassNode classNode, String sourceVersion,
                                      String targetVersion, MigrationResult result) {
        if (classNode.interfaces != null) {
            for (String iface : classNode.interfaces) {
                if (iface.contains("IForgeBlock") || iface.contains("IForgeItem")) {
                    result.addIssue(new MigrationIssue(
                        MigrationIssue.Severity.ERROR,
                        MigrationIssue.Category.REMOVED_API,
                        "Removed Forge Interface: " + iface,
                        String.format("Class '%s' implements '%s' which was removed in newer Forge versions.",
                            classNode.name, iface),
                        classNode.name,
                        "Remove this interface implementation — its methods are now in the base class."
                    ));
                }
            }
        }
    }

    private boolean isVersionInRange(String version, String rangeSpec) {
        if (version == null || rangeSpec == null || rangeSpec.isBlank()) return true;
        return version.startsWith(rangeSpec) || rangeSpec.contains(version);
    }

    private boolean isNewerThan(String version, String baseline) {
        try {
            return toComparableInt(version) > toComparableInt(baseline);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int toComparableInt(String version) {
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0].replaceAll("[^\\d]", ""));
        int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^\\d]", "")) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("[^\\d]", "")) : 0;
        // Normalize: 26.x era starts after 1.21.x — map 26.x -> 2600+minor
        // 1.x.x stays as (major*10000 + minor*100 + patch)
        if (major >= 20) {
            return major * 10000 + minor * 100 + patch;
        }
        return major * 10000 + minor * 100 + patch;
    }

    private record TargetMappings(
        Set<String> methods,
        Set<String> fields,
        Map<String, Set<String>> methodsByName,
        Map<String, Set<String>> fieldsByName
    ) {}

    private TargetMappings loadTargetMappings(Path targetMappings) {
        if (targetMappings == null || !Files.exists(targetMappings)) return null;
        try {
            MemoryMappingTree tree = new MemoryMappingTree();
            Path tinyFile = findTinyFile(targetMappings);
            MappingReader.read(tinyFile, tree);

            int targetNs = tree.getNamespaceId("intermediary");
            if (targetNs < 0) targetNs = tree.getNamespaceId("srg");
            if (targetNs < 0 && tree.getDstNamespaces().size() >= 1) targetNs = 1;
            if (targetNs < 0) return null;

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
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
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

    private List<String> findCandidateMethods(TargetMappings mappings, String ownerSlash, String originalName) {
        if (mappings == null || ownerSlash == null) return Collections.emptyList();
        String prefix = ownerSlash + "#";
        List<String> sameName = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String method : mappings.methods) {
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
        return result.stream().limit(10).toList();
    }

    private static Map<String, ApiChange> buildKnownApiChanges() {
        Map<String, ApiChange> changes = new HashMap<>();

        changes.put(
            "net/minecraft/world/item/ItemStack#getItem()Lnet/minecraft/world/item/Item;",
            new ApiChange(
                "net/minecraft/world/item/ItemStack", "getItem",
                "()Lnet/minecraft/world/item/Item;",
                "net/minecraft/world/item/ItemStack", "getItem",
                "()Lnet/minecraft/world/item/Item;",
                "1.16", "1.20", "ItemStack.getItem() signature unchanged but deprecations may apply."
            )
        );

        changes.put(
            "net/minecraft/world/entity/player/Player#hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            new ApiChange(
                "net/minecraft/world/entity/player/Player", "hurt",
                "(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
                "net/minecraft/world/entity/player/Player", "hurt",
                "(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
                "1.16", "1.20",
                "DamageSource API changed significantly in 1.19.4. DamageSource is now a record."
            )
        );

        return changes;
    }
}
