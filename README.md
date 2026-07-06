# Minecraft Mod Migrator — Project Phoenix

A desktop tool that takes a Minecraft mod `.jar` built for an older version and upgrades it to a newer one. Drop in the JAR, pick a target version, run.

## What it does

1. **Inspects** the JAR — detects mod loader (Forge / NeoForge / Fabric / Quilt), mod ID, version, and source Minecraft version
2. **Fetches mappings** — downloads Mojang official mappings for both source and target versions automatically
3. **Remaps bytecode** — uses Tiny Remapper to remap all class/method/field names to the target version
4. **Decompiles** — runs the remapped JAR through CFR to produce readable Java source
5. **Analyses API diffs** — walks the bytecode with ASM to flag removed, renamed, or changed API calls
6. **Generates a report** — a dark-themed HTML report listing every issue with severity, location, and suggested fixes
7. **Outputs** — remapped JAR + decompiled source tree + HTML report in a timestamped folder

## Requirements

- **Java 17 or newer** (JDK, not JRE) — [Download Temurin 21](https://adoptium.net/en-GB/temurin/releases/?version=21)
- Internet connection (first run downloads mappings and Gradle — cached after that)

## Build & Run

```powershell
# From the project directory:
.\gradlew run

# Or build a fat JAR:
.\gradlew fatJar
# Then run:
java -jar build\libs\MinecraftModMigrator-1.0.0-all.jar
```

## Supported Versions

| Mod Loader | Source Versions | Target Versions |
|---|---|---|
| Forge | 1.16.x → 1.20.x | 1.16.x → 1.21.x |
| NeoForge | 1.20.1+ | 1.20.1 → 1.21.x |
| Fabric | 1.16.x → 1.21.x | 1.16.x → 1.21.x |
| Quilt | 1.18.x → 1.21.x | 1.18.x → 1.21.x |

## Limitations

- **Logic rewrites** — structural API overhauls (e.g. world generation rewrite in 1.18, DamageSource becoming a record in 1.19.4) are flagged but cannot be auto-fixed. The tool tells you exactly what needs changing.
- **Recompilation** — the tool produces a remapped + decompiled source tree. Full recompilation against the target loader's API requires the mod's build environment to be set up manually.
- **Obfuscated deps** — mods that ship with obfuscated Minecraft classes embedded will require extra mapping passes.

## Output Structure

```
~/Desktop/ModMigrator-Output/
  mymod_migration_20240102_153045/
    mymod-remapped-1.21.1.jar     ← Drop this in to test loading
    sources/                       ← Decompiled Java source
      com/example/mymod/...
    migration-report.html          ← Open in browser for full issue list
```

## Cache

Mojang mappings are cached in `~/.modmigrator/cache/` after first download (~1MB per version).
