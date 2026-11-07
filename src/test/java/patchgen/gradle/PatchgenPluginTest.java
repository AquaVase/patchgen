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

    @Test public void setupOrdersExtractionBeforeOverlayAndRunDoesNotExtract() {
        String setup=runner("setupWorkspace","--dry-run").build().getOutput();
        assertTrue(setup.indexOf(":extractMapped SKIPPED")<setup.indexOf(":syncSources SKIPPED"));
        String run=runner("runClient","--dry-run").build().getOutput();
        assertFalse(run.contains(":extractMapped SKIPPED"));assertFalse(run.contains(":setupMCP SKIPPED"));
        assertTrue(run.contains(":runClient SKIPPED"));
    }

    @Test public void binaryTasksWorkOnNonMinecraftArchivesWithoutRunningCompiler() throws Exception {
        Path base=project.resolve("base.jar"),target=project.resolve("target.jar");
        ArchiveIO.write(base,Map.of("data","before".getBytes()));ArchiveIO.write(target,Map.of("data","after".getBytes()));
        Files.writeString(project.resolve("build.gradle"),"\npatchgen { baseJar=file('base.jar'); modifiedJar=file('target.jar') }\n",StandardOpenOption.APPEND);
        var result=runner("generatePatches").build();
        assertNull(result.task(":compileJava"));
        ArchiveIO.equal(ArchiveIO.read(target),ArchivePatches.restore(base,project.resolve("build/patchgen/patches.jar"),""));
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
