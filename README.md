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
| `syncSources` | Copies the configured overlay to src/resources                                                                          |
| `compileGame` | Prepares the Minecraft dependency, syncs user overlays, and compiles user code |
| `runClient` / `runServer` | Runs the compiled output via this project's JavaExec; the client uses ForgeGradle resources and natives                 |
| `generatePatches` | Encodes baseJar and modifiedJar into a patch archive, and verifies restoration entry by entry and for the whole archive |
| `restoreArchive` | Restores baseJar and inputArchive to `build/patchgen/restored.jar`                                                      |

Run `./gradlew setupWorkspace` the first time, then refresh the Gradle project in your IDE. Minecraft is a separate compile/runtime dependency with a matching sources JAR for navigation and completion. Write your own code in `src/main/java` and run `./gradlew runClient`. User output precedes the Minecraft library on the runtime classpath, allowing classes with matching package and class names to override library classes.

The library is compiled from ForgeGradle's mapped MCP source archive and stored under `.patchgen/library/`, which must not be committed. Setup and normal compilation do not extract Minecraft sources into `src/main/java`; library generation is incremental and is also wired into compilation. Existing Minecraft sources from an older setup are not deleted automatically: move or remove unmodified generated files yourself, preserving your edits. The low-level ForgeGradle `extractMapped` task still explicitly extracts sources if invoked.

This change only separates the development library from user sources. The project's `jar` still contains user output only; automatic source-patch export, merging a complete target archive, and reobfuscation for `generatePatches` are not implemented. For a complete binary-patch target, configure `modifiedJar` explicitly.

If you need to preserve overlays, you can configure `sourceOverlays.from('sources')` and `resourceOverlays.from('resources')`; in that case you should edit the overlay files, and subsequent syncs will overwrite the corresponding generated files. Deleting an overlay file will not automatically delete the old file in the generated directory.

## Configuration

| Configuration Item | Default Value / Purpose |
| --- | --- |
| `minecraftVersion`, `mcpConfig` | Required; the game version and corresponding MCPConfig version |
| `pipeline` | `joined` |
| `mappingChannel`, `mappingVersion` | `official`, minecraftVersion |
| `javaVersion` | 21; toolchain for game compilation and runtime |
| `sourcesDirectory`, `runDirectory` | `src/main/java`, `run` |
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
