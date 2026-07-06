package com.modmigrator.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.modmigrator.model.ModInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AnnotationNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JarInspector {

    private static final Pattern FORGE_VERSION_PATTERN =
        Pattern.compile("forge-(\\d+\\.\\d+\\.\\d+)");
    private static final Pattern MC_VERSION_PATTERN =
        Pattern.compile("minecraft_version\\s*=\\s*([\\d.]+)");

    public ModInfo inspect(Path jarPath) throws IOException {
        ModInfo info = new ModInfo(jarPath);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            detectModLoader(jar, info);
            readManifest(jar, info);
        }

        return info;
    }

    private void detectModLoader(JarFile jar, ModInfo info) throws IOException {
        boolean hasForgeToml   = jar.getEntry("META-INF/mods.toml") != null;
        boolean hasNeoForgeToml = jar.getEntry("META-INF/neoforge.mods.toml") != null;
        boolean hasFabricJson  = jar.getEntry("fabric.mod.json") != null;
        boolean hasQuiltJson   = jar.getEntry("quilt.mod.json") != null;
        boolean hasForgeModJar = jar.getEntry("META-INF/MANIFEST.MF") != null;

        if (hasNeoForgeToml) {
            info.setModLoader(ModInfo.ModLoader.NEOFORGE);
            readNeoForgeToml(jar, info);
        } else if (hasForgeToml) {
            info.setModLoader(ModInfo.ModLoader.FORGE);
            readForgeToml(jar, info);
        } else if (hasFabricJson) {
            info.setModLoader(ModInfo.ModLoader.FABRIC);
            readFabricJson(jar, info);
        } else if (hasQuiltJson) {
            info.setModLoader(ModInfo.ModLoader.QUILT);
            readQuiltJson(jar, info);
        } else {
            info.setModLoader(ModInfo.ModLoader.UNKNOWN);
            attemptVersionDetectionFromBytecode(jar, info);
        }
    }

    private void readManifest(JarFile jar, ModInfo info) throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) return;

        String implVersion = manifest.getMainAttributes().getValue("Implementation-Version");
        if (implVersion != null && info.getModVersion() == null) {
            info.setModVersion(implVersion);
        }

        String forgeVersion = manifest.getMainAttributes().getValue("ForgeVersion");
        if (forgeVersion != null) {
            Matcher m = FORGE_VERSION_PATTERN.matcher(forgeVersion);
            if (m.find() && info.getDetectedMinecraftVersion() == null) {
                info.setDetectedMinecraftVersion(m.group(1));
            }
        }
    }

    private void readFabricJson(JarFile jar, ModInfo info) throws IOException {
        JarEntry entry = (JarEntry) jar.getEntry("fabric.mod.json");
        if (entry == null) return;

        try (InputStream is = jar.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            if (json.has("id")) info.setModId(json.get("id").getAsString());
            if (json.has("name")) info.setModName(json.get("name").getAsString());
            if (json.has("version")) info.setModVersion(json.get("version").getAsString());
            if (json.has("description")) info.setDescription(json.get("description").getAsString());

            if (json.has("depends")) {
                JsonObject depends = json.getAsJsonObject("depends");
                List<String> deps = new ArrayList<>();
                depends.entrySet().forEach(e -> deps.add(e.getKey() + "@" + e.getValue().getAsString()));
                info.setDependencies(deps);

                if (depends.has("minecraft")) {
                    String mcVersion = depends.get("minecraft").getAsString()
                        .replaceAll("[^\\d.]", "").trim();
                    if (!mcVersion.isEmpty()) {
                        info.setDetectedMinecraftVersion(mcVersion);
                    }
                }
            }
        }
    }

    private void readQuiltJson(JarFile jar, ModInfo info) throws IOException {
        JarEntry entry = (JarEntry) jar.getEntry("quilt.mod.json");
        if (entry == null) return;

        try (InputStream is = jar.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject metadata = json.has("quilt_loader")
                ? json.getAsJsonObject("quilt_loader") : json;

            if (metadata.has("id")) info.setModId(metadata.get("id").getAsString());
            if (metadata.has("version")) info.setModVersion(metadata.get("version").getAsString());
            if (metadata.has("metadata")) {
                JsonObject meta = metadata.getAsJsonObject("metadata");
                if (meta.has("name")) info.setModName(meta.get("name").getAsString());
                if (meta.has("description")) info.setDescription(meta.get("description").getAsString());
            }

            if (metadata.has("depends")) {
                metadata.getAsJsonArray("depends").forEach(dep -> {
                    if (dep.isJsonObject()) {
                        JsonObject depObj = dep.getAsJsonObject();
                        if (depObj.has("id") && depObj.get("id").getAsString().equals("minecraft")) {
                            if (depObj.has("versions")) {
                                String ver = depObj.get("versions").getAsString()
                                    .replaceAll("[^\\d.]", "").trim();
                                if (!ver.isEmpty()) info.setDetectedMinecraftVersion(ver);
                            }
                        }
                    }
                });
            }
        }
    }

    private void readForgeToml(JarFile jar, ModInfo info) throws IOException {
        JarEntry entry = (JarEntry) jar.getEntry("META-INF/mods.toml");
        if (entry == null) return;
        parseModsToml(jar, entry, info);
    }

    private void readNeoForgeToml(JarFile jar, ModInfo info) throws IOException {
        JarEntry entry = (JarEntry) jar.getEntry("META-INF/neoforge.mods.toml");
        if (entry == null) entry = (JarEntry) jar.getEntry("META-INF/mods.toml");
        if (entry == null) return;
        parseModsToml(jar, entry, info);
    }

    private void parseModsToml(JarFile jar, JarEntry entry, ModInfo info) throws IOException {
        try (InputStream is = jar.getInputStream(entry)) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            extractTomlValue(content, "modId", info::setModId);
            extractTomlValue(content, "displayName", info::setModName);
            extractTomlValue(content, "version", info::setModVersion);
            extractTomlValue(content, "description", info::setDescription);

            Pattern mcPattern = Pattern.compile("\\[dependencies\\.[\\w-]+\\][^\\[]*"
                + "modId\\s*=\\s*\"minecraft\"[^\\[]*versionRange\\s*=\\s*\"([^\"]+)\"",
                Pattern.DOTALL);
            Matcher m = mcPattern.matcher(content);
            if (m.find()) {
                String range = m.group(1).replaceAll("[^\\d.]", "").trim();
                if (!range.isEmpty()) info.setDetectedMinecraftVersion(range);
            }
        }

        readGradleProperties(jar, info);
    }

    private void readGradleProperties(JarFile jar, ModInfo info) throws IOException {
        JarEntry gradleProps = (JarEntry) jar.getEntry("gradle.properties");
        if (gradleProps == null) return;

        try (InputStream is = jar.getInputStream(gradleProps)) {
            Properties props = new Properties();
            props.load(is);
            String mcVersion = props.getProperty("minecraft_version");
            if (mcVersion != null && !mcVersion.isBlank()) {
                info.setDetectedMinecraftVersion(mcVersion.trim());
            }
            if (info.getModVersion() == null || info.getModVersion().isBlank()) {
                String modVersion = props.getProperty("mod_version");
                if (modVersion != null) info.setModVersion(modVersion.trim());
            }
        }
    }

    private void extractTomlValue(String content, String key, java.util.function.Consumer<String> setter) {
        Pattern p = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(content);
        if (m.find()) {
            setter.accept(m.group(1));
        }
    }

    private void attemptVersionDetectionFromBytecode(JarFile jar, ModInfo info) {
        Enumeration<JarEntry> entries = jar.entries();
        int classCount = 0;
        while (entries.hasMoreElements() && classCount < 10) {
            JarEntry entry = entries.nextElement();
            if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF")) continue;

            try (InputStream is = jar.getInputStream(entry)) {
                ClassReader reader = new ClassReader(is);
                ClassNode classNode = new ClassNode();
                reader.accept(classNode, ClassReader.SKIP_CODE);

                if (classNode.visibleAnnotations != null) {
                    for (AnnotationNode ann : classNode.visibleAnnotations) {
                        if (ann.desc != null && ann.desc.contains("Mod")) {
                            info.setModLoader(ModInfo.ModLoader.FORGE);
                            return;
                        }
                    }
                }
                classCount++;
            } catch (IOException ignored) {}
        }
    }
}
