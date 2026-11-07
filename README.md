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
    mcpConfig = '1.21.1-20240808.144430'
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
| `runClient` / `runServer` | Runs the compiled output via this project's JavaExec; the client uses ForgeGradle resources and natives                 |
| `generatePatches` | Encodes baseJar and modifiedJar into a patch archive, and verifies restoration entry by entry and for the whole archive |
| `restoreArchive` | Restores baseJar and inputArchive to `build/patchgen/restored.jar`                                                      |

Run `./gradlew setupWorkspace` the first time, then refresh the Gradle project in your IDE. Minecraft is a separate compile/runtime dependency with a matching sources JAR for navigation and completion. Write your own code in `src/main/java` and run `./gradlew runClient`. User output precedes the Minecraft library on the runtime classpath, allowing classes with matching package and class names to override library classes.

The library is compiled from ForgeGradle's mapped MCP source archive and stored under `.patchgen/library/`, which must not be committed. Setup and normal compilation do not extract Minecraft sources into `src/main/java`; library generation is incremental and is also wired into compilation. Existing Minecraft sources from an older setup are not deleted automatically: move or remove unmodified generated files yourself, preserving your edits. The low-level ForgeGradle `extractMapped` task still explicitly extracts sources if invoked.

The project's `jar` still contains user output only; merging a complete target archive and reobfuscation for `generatePatches` are not implemented. For a complete binary-patch target, configure `modifiedJar` explicitly.

If you need to preserve overlays, you can configure `sourceOverlays.from('sources')` and `resourceOverlays.from('resources')`; in that case you should edit the overlay files, and subsequent syncs will overwrite the corresponding generated files. Deleting an overlay file will not automatically delete the old file in the generated directory.

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
| `modifiedJar` | The output of this project's `jar`; supports task providers and automatically establishes task dependencies |
| `outputArchive` | `build/patchgen/patches.jar` |
| `inputArchive` | Optional; an existing patch archive. When generating, its restore entries are preserved and the selected targets are overwritten |
| `archivePrefix` | Empty; for example, if an existing patch stores target classes as `notch/...`, configure `notch/` |
| `unprefixedPackages` | Empty list; class path prefixes that are not prefixed when using an existing archive |
| `additionalIncludes` | Empty list; globs allowing new entries when using an existing archive, such as `assets/**` |

When `inputArchive` is not configured, all entries of modifiedJar constitute the complete target. When `inputArchive` is configured, it is restored first, then entries with the same name are overwritten, along with new entries allowed by additionalIncludes; classes may have an archivePrefix, while resources do not. This can be used to preserve multiple namespaces of one archive. `patch.cfg`, `patch2.cfg`, and `patch3.cfg` support mapping from targets to baseline entries.

## About Patches

The encoder directly depends on `com.nothome:javaxdelta:2.0.1` and generates GDIFF v4 (header `D1 FF D1 FF 04`). It is not xdelta3/VCDIFF. Existing baseline entries are encoded as `patch/<target path>.xdelta` and `.md5`; new files are stored directly. When the archive contains mapping rules, diffs are generated only for entries covered by the rules, and other entries remain in their original file form, ensuring that manifests and similar files can be read directly. The standalone Java decoder checks boundaries and MD5, and every generation verifies complete restoration before replacing the output file.

## Build Plugin

```powershell
./gradlew clean build projectZip
./gradlew publishToMavenLocal
```
