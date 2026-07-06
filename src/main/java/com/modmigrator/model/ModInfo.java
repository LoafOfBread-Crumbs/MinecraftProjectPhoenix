package com.modmigrator.model;

import java.nio.file.Path;
import java.util.List;

public class ModInfo {

    public enum ModLoader {
        FORGE, NEOFORGE, FABRIC, QUILT, UNKNOWN
    }

    private final Path jarPath;
    private String modId;
    private String modName;
    private String modVersion;
    private String detectedMinecraftVersion;
    private ModLoader modLoader;
    private List<String> dependencies;
    private String description;

    public ModInfo(Path jarPath) {
        this.jarPath = jarPath;
        this.modLoader = ModLoader.UNKNOWN;
    }

    public Path getJarPath() { return jarPath; }
    public String getModId() { return modId; }
    public void setModId(String modId) { this.modId = modId; }
    public String getModName() { return modName; }
    public void setModName(String modName) { this.modName = modName; }
    public String getModVersion() { return modVersion; }
    public void setModVersion(String modVersion) { this.modVersion = modVersion; }
    public String getDetectedMinecraftVersion() { return detectedMinecraftVersion; }
    public void setDetectedMinecraftVersion(String ver) { this.detectedMinecraftVersion = ver; }
    public ModLoader getModLoader() { return modLoader; }
    public void setModLoader(ModLoader modLoader) { this.modLoader = modLoader; }
    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getFileName() {
        return jarPath.getFileName().toString();
    }

    @Override
    public String toString() {
        return String.format("ModInfo{id='%s', name='%s', loader=%s, mcVersion='%s'}",
            modId, modName, modLoader, detectedMinecraftVersion);
    }
}
