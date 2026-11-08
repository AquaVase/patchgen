# patchgen

A Gradle plugin for setting up an MCP workspace and developing binary patches based on ForgeGradle.

## Usage

In `settings.gradle`:

```groovy
pluginManagement {
    includeBuild('../patchgen')
    repositories {
        maven { url = 'https://maven.creeperhost.net' }
        maven { url = 'https://maven.minecraftforge.net' }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins { id 'patchgen.settings' }
rootProject.name = 'MyProject'
```

In `build.gradle`:

```groovy
plugins { id 'patchgen' }
patchgen {
    minecraftVersion = '1.21.1'
    mcpConfig = '1.21.1-20240808.132146'
    javaVersion = 21
    // baseJar defaults to the MCP Minecraft client JAR; override it if needed:
    // baseJar = file('inputs/base.jar')
    outputArchive = layout.buildDirectory.file('patches.jar')
    // By default, patches are generated against the current project's jar output; you can also specify a separate target JAR:
    // modifiedJar = file('inputs/modified.jar')
}
```

You can use `-Porg.gradle.java.installations.paths=...` to specify the JDK path.

## Workflow

| Task | Behavior                                                                                                                |
| --- |-------------------------------------------------------------------------------------------------------------------------|
| `setupWorkspace` | Builds a separate mapped Minecraft library with attached MCP sources, downloads assets, and applies user overlays |
| `prepareMinecraftLibrary` | Compiles mapped MCP sources and creates the local dependency JAR and sources JAR |
| `genSourcePatches` | Generates `patches/<package>/<Class>.java.patch` from modified or added Java sources against mapped MCP sources |
| `restoreSourceFromPatches` | Restores only patched and added Java files into `sourcesDirectory` using mapped MCP sources |
| `syncSources` | Copies the configured overlay to src/resources                                                                          |
| `compileGame` | Prepares the Minecraft dependency, syncs user overlays, and compiles user code |
| `copyAssets` | Downloads and copies Minecraft assets into `runDirectory/assets` |
| `runClient` / `runServer` | Launches Minecraft with user output and the mapped library; the client prepares assets/natives and the server forwards console input |
| `generatePatches` | Encodes baseJar and modifiedJar into a patch archive, and verifies restoration entry by entry and for the whole archive |
| `restoreArchive` | Restores baseJar and inputArchive to `build/patchgen/restored.jar`                                                      |

Run `./gradlew setupWorkspace` the first time, then refresh the Gradle project in your IDE. Minecraft is a separate compile/runtime dependency with a matching sources JAR for navigation and completion. Write your own code in `src/main/java` and run `./gradlew runClient`. User output precedes the Minecraft library on the runtime classpath, allowing classes with matching package and class names to override library classes.

The library is compiled from ForgeGradle's mapped MCP source archive and stored under `.patchgen/library/`, which must not be committed. Setup and normal compilation do not extract Minecraft sources into `src/main/java`; library generation is incremental and is also wired into compilation. Existing Minecraft sources from an older setup are not deleted automatically: move or remove unmodified generated files yourself, preserving your edits. The low-level ForgeGradle `extractMapped` task still explicitly extracts sources if invoked.

The project's `jar` still contains user output only; merging a complete Minecraft target archive is not automatic. For a complete binary-patch target, configure `modifiedJar` explicitly. Dual Notch/SRG mode remaps that development-named JAR into both namespaces before encoding.

If you need to preserve overlays, you can configure `sourceOverlays.from('sources')` and `resourceOverlays.from('resources')`; in that case you should edit the overlay files, and subsequent syncs will overwrite the corresponding generated files. Deleting an overlay file will not automatically delete the old file in the generated directory.

## Launch Minecraft

```powershell
./gradlew runClient
./gradlew runServer
```

These tasks compile user code and prepare the Minecraft library automatically, without extracting sources or restoring source patches. The client downloads assets and copies them to `run/assets`, extracts native libraries, then launches `net.minecraft.client.main.Main` with the configured Java toolchain. Its arguments include the game version, game directory, local asset directory, asset-index ID read from Minecraft metadata, and the development access token `0`. Following MCP-Reborn's launch configuration, it uses `-XX:StackShadowPages=32`, plus `-XstartOnFirstThread` on macOS.

The client working directory defaults to `run`; the server uses `run/server` and accepts console input. Both follow `patchgen.runDirectory`. `setupWorkspace` also copies assets, and `copyAssets` can be invoked separately. Additional client arguments can be supplied with `./gradlew runClient --args="--username Developer"` or through the normal Gradle JavaExec configuration:

```groovy
tasks.named('runClient', JavaExec) {
    maxHeapSize = '4g'
    args '--username', 'Developer'
}
tasks.named('runServer', JavaExec) {
    args '--nogui'
}
```

## Source patches

Copy only Minecraft classes you want to edit from the attached MCP sources into `src/main/java`, and edit them as normal Java files. Added Java classes may also live there. Then run:

```powershell
./gradlew genSourcePatches
# On another checkout, with the same Minecraft, MCP and mapping configuration:
./gradlew restoreSourceFromPatches
```

Both tasks obtain their baseline from ForgeGradle's `srg2mcp` output automatically, without compiling Minecraft or extracting its complete source tree. Source patches use diff4j 1.3 to generate unified diffs with three context lines, UTF-8 and LF line endings. Restoration validates paths, hunk ranges and exact context before writing any files. File-final newline differences are preserved; CRLF/CR line endings are normalized to LF.

The source directory is a sparse set of overrides: Minecraft files absent from it are not treated as deletions, and files identical to the baseline produce no patches. Added classes use `/dev/null` as their baseline. Generation replaces matching `*.java.patch` files and removes stale ones in the configured patch directory; other files such as a README are preserved. Resources are outside these tasks' scope.

Restoration explicitly overwrites files named by the patches, including local edits to those files, and preserves unrelated files. It does not delete overrides whose patches have been removed. Run it explicitly when initializing or resetting an editing workspace; it is not automatically run by setup or compilation. Keep patches in version control; generated Minecraft sources and `.patchgen/` should remain local. You can configure the directories:

```groovy
patchgen {
    sourcesDirectory = layout.projectDirectory.dir('src/main/java')
    sourcePatchesDirectory = layout.projectDirectory.dir('patches')
}
```

## Configuration

| Configuration Item | Default Value / Purpose |
| --- | --- |
| `minecraftVersion`, `mcpConfig` | Required; the game version and corresponding MCPConfig version |
| `pipeline` | `joined` |
| `mappingChannel`, `mappingVersion` | `official`, minecraftVersion |
| `javaVersion` | 21; toolchain for game compilation and runtime |
| `sourcesDirectory`, `runDirectory` | `src/main/java`, `run` |
| `sourcePatchesDirectory` | `patches`; input/output directory for the source-patch tasks |
| `clientMainClass`, `serverMainClass` | `net.minecraft.client.main.Main`, `net.minecraft.server.Main` |
| `baseJar` | Patch baseline JAR; defaults to the original Minecraft client JAR resolved and cached by ForgeGradle for `minecraftVersion` |
| `modifiedJar` | The output of this project's `jar`; supports task providers. In dual mode this is the development-named input to both remapping tasks |
| `notchModifiedJar` | Notch-named target override; defaults to `modifiedJar` in single mode, or its remapped Notch output in dual mode |
| `srgModifiedJar` | SRG-named target override; defaults to the SRG-remapped `modifiedJar` when dual mode is enabled |
| `srgBaseJar` | Defaults to ForgeGradle's `net.minecraft:client:<mcpConfig>:srg` when dual mode is enabled |
| `outputArchive` | `build/patchgen/patches.jar` |
| `inputArchive` | Optional; an existing patch archive. When generating, its restore entries are preserved and the selected targets are overwritten |
| `patchRoot` | `patch/`; archive-internal directory containing `.xdelta` and `.md5` entries. Empty places them at the archive root |
| `notchPrefix` | Empty; target class-path prefix for original/Notch bytecode, e.g. `notch/`. Renamed from `archivePrefix` |
| `srgPrefix` | Empty disables SRG generation; e.g. `srg/` enables dual Notch/SRG generation and restoration |
| `unprefixedPackages` | Empty list; class path prefixes that are not prefixed when using an existing archive |
| `additionalIncludes` | Empty list; globs allowing new entries when using an existing archive, such as `assets/**` |

When `inputArchive` is not configured, incoming target JAR entries constitute the complete target. Classes receive the configured namespace prefix even without an input archive. When `inputArchive` is configured, it is restored using the selected bases and layout first, then existing entries are overwritten along with new entries allowed by `additionalIncludes` (matched against incoming paths before prefixing). Other namespaces are preserved. `patch.cfg`, `patch2.cfg`, and `patch3.cfg` support mapping from target paths to entries in the corresponding namespace's baseline.

## Dual Notch/SRG binary patches

```groovy
patchgen {
    patchRoot = 'patch/' // Or 'custom/deltas/'; this is inside the output JAR.
    notchPrefix = 'notch/'
    srgPrefix = 'srg/'
}
```

Run `./gradlew generatePatches` to create both sets of patches in one archive. A non-empty `srgPrefix` enables dual mode; both namespace prefixes must then be non-empty and must not overlap. Trailing slashes are added automatically. The old `archivePrefix` configuration must be renamed to `notchPrefix`.

For obfuscated Minecraft versions, `remapNotchTarget` and `remapSrgTarget` use ForgeGradle's `createMcp2Obf` and `createMcp2Srg` mappings to transform `modifiedJar`, including bytecode references. For Minecraft versions that ForgeGradle identifies as unobfuscated, both targets use the development output directly and `srgBaseJar` defaults to `baseJar`, since a separate SRG client artifact is not available. The SRG baseline is resolved lazily only when SRG mode is enabled. Explicit `notchModifiedJar`, `srgModifiedJar`, and `srgBaseJar` values override these defaults, so already-remapped external targets do not run the corresponding remapping tasks. Single-namespace mode retains the existing behavior: `modifiedJar` is assumed to already use its baseline's naming; it is not automatically remapped.

For example, the output can contain:

```text
patch/notch/a.class.xdelta
patch/notch/a.class.md5
patch/srg/net/minecraft/Example.class.xdelta
patch/srg/net/minecraft/Example.class.md5
```

Notch entries are encoded against `baseJar`; SRG entries are encoded against `srgBaseJar`. Resources and classes matching `unprefixedPackages` are included once, from the Notch target, without a namespace prefix. These prefixes only organize archive paths; bytecode remapping happens separately. Restore with the same `patchRoot`, `notchPrefix`, `srgPrefix` and baselines via `restoreArchive`. Both entry-level and complete-archive round-trip verification use the appropriate baseline for each namespace.

## About Patches

The encoder directly depends on `com.nothome:javaxdelta:2.0.1` and generates GDIFF v4 (header `D1 FF D1 FF 04`). It is not xdelta3/VCDIFF. Existing baseline entries are encoded as `patch/<target path>.xdelta` and `.md5`; new files are stored directly. When the archive contains mapping rules, diffs are generated only for entries covered by the rules, and other entries remain in their original file form, ensuring that manifests and similar files can be read directly. The standalone Java decoder checks boundaries and MD5, and every generation verifies complete restoration before replacing the output file.

## Build Plugin

```powershell
./gradlew clean build projectZip
./gradlew publishToMavenLocal
```
