# ModMigrator Session Memory — 2026-07-14

## Current Status

Active task: **MixinAnalyzer Enhancements and Integration**

The original Mixin injection-target crash (`FireBlockMixin#ac$getStateWithAgeDrowned`) has been **fixed automatically**. The mod now crashes later during initialization because of a different Minecraft API change in entity registration.

## What Was Implemented Tonight

1. **Mixin error reports with ranked candidate methods**
   - `MixinAnalyzer.scanMixinClass` collects candidate methods when an injection target cannot be verified and ranks them (same name first).

2. **Patch suggestion markdown files**
   - `MixinAnalyzer.writePatchSuggestions` emits a `.md` file per analyzed jar listing unverified Mixin targets and candidate replacements.

3. **Forge / NeoForge / Quilt mapping fetcher**
   - `MappingFetcher.fetchForgeSrgMappings` downloads MCPConfig, extracts `joined.tsrg`, and converts it to Tiny2.
   - `MigrationPipeline` selects Fabric intermediary for Fabric/Quilt and SRG for Forge/NeoForge.

4. **Safe auto-apply for bytecode-compatible Mixin descriptor changes**
   - `MixinAnalyzer.applyRefmapFixes` rewrites refmap entries when exactly one compatible target descriptor exists.

5. **Aggressive bytecode callback descriptor rewrite**
   - `MixinAnalyzer.rewriteMixinCallbacks` rewrites the actual Mixin callback method bytecode so its descriptor matches the new target signature (parameter type substitution via ASM `MethodRemapper`).
   - This is what finally resolved the `FireBlockMixin` crash.

6. **Dynamic API diff analysis against target mappings**
   - `ApiDiffAnalyzer` now loads the target version's mappings and checks every direct `net/minecraft/*` method call in the mod bytecode against them.
   - Reports an `ERROR` with candidate signatures if the call cannot be verified.
   - Filters out compiler-generated enum `values()` / `valueOf(String)` calls to reduce noise.

## Test Results (aquatic_creepers 1.21 → 1.21.11, Fabric)

- **Before bytecode rewrite:** crash in `FireBlockMixin#ac$getStateWithAgeDrowned` due to callback descriptor mismatch (`LevelAccessor` vs old `World`/`Level` parameter).
- **After bytecode rewrite:** past Mixin initialization.
- **Current crash:** `NoSuchMethodError: EntityType$Builder.method_5905(String)` in `AquaticCreepers_Entities.lambda$static$0`.
  - In 1.21.2+, `EntityType.Builder.build(String id)` was changed to `EntityType.Builder.build(RegistryKey<EntityType<T>> key)`.
  - This is a direct API usage change, not a Mixin, so the tool detects it but does not yet auto-fix it.

- **Latest report:** 40 errors, 103 warnings.
  - The 40 errors are real API signature changes between 1.21.1 and 1.21.11 (e.g., `Mth.float→double`, renderer method signatures, `Level.getGameTime`, `LivingEntity.hurt`, `PathfinderMob` methods).
  - The tool is correctly identifying them; they are not false positives.

## Key Files Modified

- `src/main/java/com/modmigrator/service/MappingFetcher.java`
- `src/main/java/com/modmigrator/service/MigrationPipeline.java`
- `src/main/java/com/modmigrator/service/MixinAnalyzer.java`
- `src/main/java/com/modmigrator/service/ApiDiffAnalyzer.java`

## TODO List (Updated)

- [x] Improve Mixin error report with ranked candidate methods
- [x] Generate patch instruction file (.md) for each unverified Mixin
- [x] Add Forge/NeoForge mapping fetcher for global loader support
- [x] Implement safe auto-apply for bytecode-compatible Mixin descriptor changes
- [x] Implement aggressive bytecode callback descriptor rewrite for auto-fixed Mixins
- [x] Enhance ApiDiffAnalyzer to detect invalid direct Minecraft API calls against target mappings
- [ ] Reduce remaining API diff false positives (currently minimal; 40 errors look real)
- [ ] Decide next auto-fix strategy for the remaining 40 errors / startup crash

## Next Steps / Decisions for Tomorrow

Pick one path (or a combination):

1. **Auto-fix common simple patterns**
   - Examples: `Mth.method_15374(F)F` → `(D)F`, renderer `scale/shouldRender` signatures that gained a `double`.
   - Narrow, relatively safe, will reduce the 40 error count.

2. **Auto-fix the entity registration startup blocker**
   - Transform `EntityType.Builder.build(String)` calls to `EntityType.Builder.build(RegistryKey<EntityType<T>>)`.
   - Requires injecting bytecode to build a `RegistryKey` from the original `String` ID using `RegistryKey.create(Registries.ENTITY_TYPE, new Identifier(id))`.
   - Once fixed, retest to discover the next crash.

3. **Accept bytecode-only limits and improve manual-fix UX**
   - Generate patch files for API issues (not just Mixins).
   - Group errors by source file in the report.
   - Add Yarn-to-intermediary mapping in report output so users know which human-named methods changed.
   - The decompiled sources are already produced when `config.isDecompileSource()` is true.

## Notes for GitHub Upload

- All changes compile successfully with `./gradlew compileJava`.
- No temporary/debug files were intentionally created in the repo.
- The `SESSION_MEMORY.md` file is safe to commit or delete after review.
- Latest test output folders are under `C:\Users\bradl\Downloads\ModMigrator-Output\aquatic_creepers_migration_20260714_*` and are **not** in the repo.
