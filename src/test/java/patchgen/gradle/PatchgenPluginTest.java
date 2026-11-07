package patchgen.gradle;

import java.nio.file.*;
import java.util.*;
import org.gradle.testkit.runner.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PatchgenPluginTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    Path project;

    @Before public void fixture() throws Exception {
        project=temp.newFolder("game project with spaces").toPath();
        Files.writeString(project.resolve("settings.gradle"),"plugins { id 'patchgen.settings' }; rootProject.name='game'\n");
        Files.writeString(project.resolve("build.gradle"),"""
            plugins { id 'patchgen' }
            patchgen {
                minecraftVersion = '26.2'
                mcpConfig = '26.2-20260616.103818'
                javaVersion = 25
            }
            tasks.register('inspectPatchgen') {
                doLast {
                    assert plugins.hasPlugin('net.minecraftforge.gradle.patcher')
                    assert project(':patchgenMinecraft').plugins.hasPlugin('net.minecraftforge.gradle.mcp')
                    assert patcher.parent.get() == project(':patchgenMinecraft')
                    assert patcher.mcVersion.get() == patchgen.minecraftVersion.get()
                    assert patcher.patchedSrc.get().asFile == file('src/main/java')
                    assert tasks.runClient instanceof JavaExec
                    assert !tasks.names.contains('setupMcp')
                    println 'FORGEGRADLE_REQUIRED_AND_SAME_BUILD'
                }
            }
            """);
        String javaHomes=System.getProperty("patchgen.testJavaHomes","");
        Files.writeString(project.resolve("gradle.properties"),"org.gradle.java.installations.paths="+javaHomes.replace('\\','/')+"\n");
    }

    GradleRunner runner(String...arguments) {
        var args=new ArrayList<>(List.of(arguments));args.addAll(List.of("--stacktrace","--max-workers=2","-Dorg.gradle.jvmargs=-Xmx768m"));
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(args).withPluginClasspath();
    }

    @Test public void hardDependencyIsAppliedAndGameRunsInSameBuild() {
        assertTrue(runner("inspectPatchgen").build().getOutput().contains("FORGEGRADLE_REQUIRED_AND_SAME_BUILD"));
        assertFalse(Files.exists(project.resolve("dev/MCP-Reborn")));
    }

    @Test public void secondMinecraftVersionIsConfigurationNotPluginCode() throws Exception {
        Path build=project.resolve("build.gradle");Files.writeString(build,Files.readString(build).replace("26.2-20260616.103818","1.21.1-20240808.144430").replace("26.2","1.21.1").replace("javaVersion = 25","javaVersion = 21"));
        assertTrue(runner("inspectPatchgen").build().getOutput().contains("FORGEGRADLE_REQUIRED_AND_SAME_BUILD"));
    }

    @Test public void setupAndRunUseLibraryWithoutExtractingSources() {
        String setup=runner("setupWorkspace","--dry-run").build().getOutput();
        assertFalse(setup.contains(":extractMapped SKIPPED"));
        assertTrue(setup.contains(":minecraftLibraryJar SKIPPED"));
        assertTrue(setup.contains(":minecraftLibrarySourcesJar SKIPPED"));
        assertTrue(setup.contains(":copyAssets SKIPPED"));
        String run=runner("runClient","--dry-run").build().getOutput();
        assertFalse(run.contains(":extractMapped SKIPPED"));
        assertTrue(run.contains(":prepareMinecraftLibrary SKIPPED"));
        assertTrue(run.contains(":copyAssets SKIPPED"));
        assertTrue(run.contains(":extractNatives SKIPPED"));
        assertTrue(run.contains(":runClient SKIPPED"));
    }

    @Test public void gameLaunchUsesLocalAssetsNativesJvmArgumentsAndRuntimeLibrary() throws Exception {
        ArchiveIO.write(project.resolve("mapped-sources.jar"),Map.of("library/Marker.java",
            "package library; public class Marker { public static String value() { return \"runtime-library\"; } }".getBytes()));
        Path assets=project.resolve("fixture-assets/indexes");Files.createDirectories(assets);
        Files.writeString(assets.resolve("fixture.json"),"{}");
        Path natives=project.resolve("fixture-natives");Files.createDirectories(natives);
        Files.writeString(project.resolve("fixture-version.json"),"{\"assetIndex\":{\"id\":\"fixture\"}}");
        Path source=project.resolve("src/main/java/demo/LaunchProbe.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"""
            package demo;
            import java.nio.file.*;
            import java.util.*;
            public class LaunchProbe {
                public static void main(String[] args) throws Exception {
                    if (!library.Marker.value().equals("runtime-library")) throw new AssertionError("runtime classpath");
                    Path cwd = Path.of("").toAbsolutePath();
                    if (args.length == 0) {
                        if (!cwd.endsWith("custom run/server")) throw new AssertionError(cwd);
                        System.out.println("SERVER_LAUNCH_OK");
                        return;
                    }
                    var options = new HashMap<String,String>();
                    for (int i=0;i<args.length;i+=2) options.put(args[i],args[i+1]);
                    if (!cwd.endsWith("custom run")) throw new AssertionError(cwd);
                    if (!Path.of(options.get("--gameDir")).equals(cwd)) throw new AssertionError(options);
                    if (!options.get("--version").equals("26.2")) throw new AssertionError(options);
                    if (!options.get("--accessToken").equals("0")) throw new AssertionError(options);
                    if (!options.get("--assetIndex").equals("fixture")) throw new AssertionError(options);
                    if (!options.get("--username").equals("PatchgenTest")) throw new AssertionError(options);
                    Path assets = Path.of(options.get("--assetsDir"));
                    if (!assets.equals(cwd.resolve("assets"))) throw new AssertionError(assets);
                    if (!Files.isRegularFile(assets.resolve("indexes/fixture.json"))) throw new AssertionError("assets not copied");
                    if (!Path.of(System.getProperty("java.library.path")).endsWith("fixture-natives")) throw new AssertionError("native path");
                    if (!java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:StackShadowPages=32")) throw new AssertionError("JVM options");
                    System.out.println("CLIENT_LAUNCH_OK");
                }
            }
            """);
        Files.writeString(project.resolve("build.gradle"),"""

            abstract class FixtureDownloadAssets extends net.minecraftforge.gradle.common.tasks.DownloadAssets {
                @Override File getOutput() { return project.file('fixture-assets') }
            }
            tasks.replace('downloadAssets', FixtureDownloadAssets).configure {
                enabled = false
                meta = file('fixture-version.json')
            }
            tasks.named('extractNatives') { enabled = false; output = file('fixture-natives') }
            patchgen {
                runDirectory = layout.projectDirectory.dir('custom run')
                clientMainClass = 'demo.LaunchProbe'
                serverMainClass = 'demo.LaunchProbe'
            }
            tasks.named('srg2mcp') {
                enabled = false
                input = file('mapped-sources.jar')
                mappings = file('mapped-sources.jar')
                output = file('mapped-sources.jar')
            }
            afterEvaluate { configurations.minecraftImplementation.dependencies.clear() }
            tasks.named('runClient') { args '--username', 'PatchgenTest' }
            """,StandardOpenOption.APPEND);
        var client=runner("runClient").build();
        assertTrue(client.getOutput().contains("CLIENT_LAUNCH_OK"));
        assertNull(client.task(":extractMapped"));assertNull(client.task(":restoreSourceFromPatches"));
        var server=runner("runServer").build();
        assertTrue(server.getOutput().contains("SERVER_LAUNCH_OK"));
        assertNull(server.task(":copyAssets"));assertNull(server.task(":extractNatives"));
    }

    @Test public void mappedLibrarySupportsCompilationSourceAttachmentAndUserOverrides() throws Exception {
        ArchiveIO.write(project.resolve("mapped-sources.jar"),Map.of(
            "net/minecraft/Example.java","package net.minecraft; public class Example { public static String value() { return \"library\"; } }".getBytes(),
            "sample-resource.txt","resource".getBytes()));
        Path source=project.resolve("src/main/java/demo/Main.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"package demo; public class Main { public static void main(String[] args) { System.out.println(net.minecraft.Example.value()); } }");
        Files.writeString(project.resolve("build.gradle"),"""

            tasks.named('srg2mcp') {
                enabled = false
                input = file('mapped-sources.jar')
                mappings = file('mapped-sources.jar')
                output = file('mapped-sources.jar')
            }
            afterEvaluate { configurations.minecraftImplementation.dependencies.clear() }
            tasks.register('runLibraryExample', JavaExec) {
                dependsOn tasks.classes
                classpath = sourceSets.main.runtimeClasspath
                mainClass = 'demo.Main'
                javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
            }
            tasks.register('inspectLibrarySources') {
                dependsOn tasks.prepareMinecraftLibrary
                doLast {
                    def result = dependencies.createArtifactResolutionQuery()
                        .forModule('patchgen.local', 'minecraft', 'mapped')
                        .withArtifacts(org.gradle.jvm.JvmLibrary, org.gradle.language.base.artifact.SourcesArtifact)
                        .execute()
                    def artifacts = result.resolvedComponents.collectMany {
                        it.getArtifacts(org.gradle.language.base.artifact.SourcesArtifact)
                    }
                    assert artifacts.size() == 1
                    assert artifacts.first().file.name == 'minecraft-mapped-sources.jar'
                }
            }
            """,StandardOpenOption.APPEND);
        assertTrue(runner("runLibraryExample","inspectLibrarySources").build().getOutput().contains("library"));
        assertFalse(Files.exists(project.resolve("src/main/java/net/minecraft/Example.java")));
        var library=ArchiveIO.read(project.resolve(".patchgen/library/patchgen/local/minecraft/mapped/minecraft-mapped.jar"));
        assertTrue(library.containsKey("net/minecraft/Example.class"));
        assertTrue(library.containsKey("sample-resource.txt"));
        assertFalse(library.containsKey("net/minecraft/Example.java"));
        Path override=project.resolve("src/main/java/net/minecraft/Example.java");Files.createDirectories(override.getParent());
        Files.writeString(override,"package net.minecraft; public class Example { public static String value() { return \"user-override\"; } }");
        assertTrue(runner("runLibraryExample").build().getOutput().contains("user-override"));
    }

    @Test public void binaryTasksWorkOnNonMinecraftArchivesWithoutRunningCompiler() throws Exception {
        Path base=project.resolve("base.jar"),target=project.resolve("target.jar");
        ArchiveIO.write(base,Map.of("data","before".getBytes()));ArchiveIO.write(target,Map.of("data","after".getBytes()));
        Files.writeString(project.resolve("build.gradle"),"\npatchgen { baseJar=file('base.jar'); modifiedJar=file('target.jar') }\n",StandardOpenOption.APPEND);
        var result=runner("generatePatches").build();
        assertNull(result.task(":compileJava"));
        ArchiveIO.equal(ArchiveIO.read(target),ArchivePatches.restore(base,project.resolve("build/patchgen/patches.jar"),""));
    }

    @Test public void sourcePatchTasksRoundTripWithoutCompilingOrExtractingMinecraft() throws Exception {
        ArchiveIO.write(project.resolve("mapped-sources.jar"),Map.of(
            "net/minecraft/Example.java","class Example { int value = 1; }\n".getBytes(),
            "net/minecraft/Other.java","class Other {}\n".getBytes()));
        Files.writeString(project.resolve("build.gradle"),"""

            patchgen {
                sourcesDirectory = layout.projectDirectory.dir('custom-src')
                sourcePatchesDirectory = layout.projectDirectory.dir('custom-patches')
            }
            tasks.named('srg2mcp') {
                enabled = false
                input = file('mapped-sources.jar')
                mappings = file('mapped-sources.jar')
                output = file('mapped-sources.jar')
            }
            """,StandardOpenOption.APPEND);
        Path source=project.resolve("custom-src/net/minecraft/Example.java");Files.createDirectories(source.getParent());
        String target="class Example { int value = 2; }\n";Files.writeString(source,target);
        var generated=runner("genSourcePatches").build();
        assertNull(generated.task(":compileMinecraftLibrary"));assertNull(generated.task(":extractMapped"));
        assertTrue(Files.exists(project.resolve("custom-patches/net/minecraft/Example.java.patch")));
        Files.delete(source);
        var restored=runner("restoreSourceFromPatches").build();
        assertNull(restored.task(":compileJava"));assertNull(restored.task(":extractMapped"));
        assertEquals(target,Files.readString(source));
        assertFalse(Files.exists(source.resolveSibling("Other.java")));
        Files.writeString(source,"unsaved-to-patches edit");runner("restoreSourceFromPatches").build();
        assertEquals(target,Files.readString(source));
        assertEquals(TaskOutcome.UP_TO_DATE,runner("genSourcePatches").build().task(":genSourcePatches").getOutcome());
    }

    @Test public void baseJarDefaultsToMinecraftClientForBothBinaryTasks() throws Exception {
        Files.writeString(project.resolve("build.gradle"),"""

            tasks.register('inspectBaseJar') {
                doLast {
                    def client = patchgen.baseJar.get().asFile
                    assert client.isFile()
                    assert client.name == 'client.jar'
                    assert client.parentFile.name == patchgen.minecraftVersion.get()
                    assert tasks.generatePatches.baseJar.get().asFile == client
                    assert tasks.restoreArchive.baseJar.get().asFile == client
                    println 'DEFAULT_CLIENT_BASE_JAR'
                }
            }
            """,StandardOpenOption.APPEND);
        assertTrue(runner("inspectBaseJar").build().getOutput().contains("DEFAULT_CLIENT_BASE_JAR"));
    }

    @Test public void missingSettingsPluginFailsClearly() throws Exception {
        Files.writeString(project.resolve("settings.gradle"),"rootProject.name='game'");
        assertTrue(runner("help").buildAndFail().getOutput().contains("Apply 'patchgen.settings'"));
    }

    @Test public void missingRequiredVersionFailsClearly() throws Exception {
        Files.writeString(project.resolve("build.gradle"),"plugins { id 'patchgen' }");
        assertTrue(runner("help").buildAndFail().getOutput().contains("minecraftVersion and patchgen.mcpConfig must be configured"));
    }
}
