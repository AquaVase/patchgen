package patchgen.gradle;

import java.io.File;
import java.util.List;
import net.minecraftforge.gradle.mcp.MCPPlugin;
import net.minecraftforge.gradle.mcp.MCPExtension;
import net.minecraftforge.gradle.patcher.PatcherPlugin;
import net.minecraftforge.gradle.patcher.PatcherExtension;
import net.minecraftforge.gradle.common.tasks.DownloadAssets;
import net.minecraftforge.gradle.common.tasks.ExtractNatives;
import net.minecraftforge.gradle.common.util.Utils;
import net.minecraftforge.gradle.common.util.VersionJson;
import org.gradle.api.*;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.*;

/** ForgeGradle is an implementation dependency and is applied unconditionally. */
public class PatchgenPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        Project parent=project.findProject(PatchgenSettingsPlugin.MCP_PROJECT);
        if(parent==null)throw new GradleException("Apply 'patchgen.settings' in settings.gradle before applying 'patchgen'.");
        if(parent==project)throw new GradleException("Apply patchgen to the game project, not the MCP parent.");
        var ext=project.getExtensions().create("patchgen",PatchgenExtension.class);
        ext.getPipeline().convention("joined");ext.getMappingChannel().convention("official");
        ext.getMappingVersion().convention(ext.getMinecraftVersion());ext.getJavaVersion().convention(21);
        ext.getSourcesDirectory().convention(project.getLayout().getProjectDirectory().dir("src/main/java"));
        ext.getRunDirectory().convention(project.getLayout().getProjectDirectory().dir("run"));
        ext.getArchivePrefix().convention("");ext.getUnprefixedPackages().convention(List.of());ext.getAdditionalIncludes().convention(List.of());
        ext.getClientMainClass().convention("net.minecraft.client.main.Main");ext.getServerMainClass().convention("net.minecraft.server.Main");
        ext.getOutputArchive().convention(project.getLayout().getBuildDirectory().file("patchgen/patches.jar"));

        // Validate before ForgeGradle's afterEvaluate callback reads these properties.
        project.afterEvaluate(p->{
            if(!ext.getMinecraftVersion().isPresent()||!ext.getMcpConfig().isPresent())
                throw new GradleException("patchgen.minecraftVersion and patchgen.mcpConfig must be configured.");
        });
        parent.getPluginManager().apply(MCPPlugin.class);
        var mcp=parent.getExtensions().getByType(MCPExtension.class);
        mcp.setConfig(ext.getMcpConfig());mcp.getPipeline().set(ext.getPipeline());
        parent.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of));
        project.getPluginManager().apply(PatcherPlugin.class);
        project.getPluginManager().apply("idea");
        var patcher=project.getExtensions().getByType(PatcherExtension.class);
        patcher.getParent().set(parent);patcher.getMcVersion().set(ext.getMinecraftVersion());
        patcher.getPatchedSrc().set(ext.getSourcesDirectory());
        patcher.getMappingChannel().set(ext.getMappingChannel());patcher.getMappingVersion().set(ext.getMappingVersion());
        project.evaluationDependsOn(parent.getPath());
        project.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of));
        project.getTasks().withType(JavaCompile.class).configureEach(task->{
            task.getOptions().setEncoding("UTF-8");task.getOptions().setFork(true);task.getOptions().getForkOptions().setMemoryMaximumSize("6g");
        });
        var main=project.getExtensions().getByType(SourceSetContainer.class).named("main");
        var sync=project.getTasks().register("syncSources",SyncSourcesTask.class,task->{
            task.setGroup("patchgen");task.setDescription("Overlay configured sources and resources into this project.");
            task.getSourceOverlays().from(ext.getSourceOverlays());task.getResourceOverlays().from(ext.getResourceOverlays());
            task.getSourcesDirectory().set(ext.getSourcesDirectory());
            task.getResourcesDirectory().set(project.getLayout().getProjectDirectory().dir("src/main/resources"));
            task.mustRunAfter("extractMapped");
        });
        project.getTasks().named("compileJava").configure(task->task.dependsOn(sync));
        project.getTasks().named("processResources").configure(task->task.dependsOn(sync));
        var setup=project.getTasks().register("setupWorkspace",task->{
            task.setGroup("patchgen");task.setDescription("Extract ForgeGradle game sources, download assets and overlay sources (overwrites generated sources).");
            task.dependsOn("extractMapped","downloadAssets",sync);
        });
        project.getTasks().register("compileGame",task->{task.setGroup("patchgen");task.dependsOn("classes");});
        var assets=project.getTasks().named("downloadAssets",DownloadAssets.class);
        var natives=project.getTasks().named("extractNatives",ExtractNatives.class);
        var toolchains=project.getExtensions().getByType(JavaToolchainService.class);
        project.getTasks().register("runClient",JavaExec.class,task->{
            task.setGroup("patchgen");task.setDescription("Run the compiled client in this Gradle build.");
            task.dependsOn("classes",assets,natives);
            task.getMainClass().set(ext.getClientMainClass());task.setClasspath(main.get().getRuntimeClasspath());
            task.getJavaLauncher().set(toolchains.launcherFor(spec->spec.getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of))));
            task.doFirst(t->{
                File run=ext.getRunDirectory().get().getAsFile();run.mkdirs();task.setWorkingDir(run);
                VersionJson metadata;
                try { metadata=Utils.loadJson(assets.get().getMeta().get().getAsFile(),VersionJson.class); }
                catch(java.io.IOException e) { throw new GradleException("Cannot read Minecraft asset metadata",e); }
                task.args("--gameDir",run.getAbsolutePath(),"--version",ext.getMinecraftVersion().get(),
                    "--assetsDir",assets.get().getOutput().getAbsolutePath(),"--assetIndex",metadata.assetIndex.id,"--accessToken","0");
                task.systemProperty("java.library.path",natives.get().getOutput().get().getAsFile().getAbsolutePath());
            });
            if(System.getProperty("os.name").toLowerCase().contains("mac"))task.jvmArgs("-XstartOnFirstThread");
        });
        project.getTasks().register("runServer",JavaExec.class,task->{
            task.setGroup("patchgen");task.dependsOn("classes");task.getMainClass().set(ext.getServerMainClass());
            task.setClasspath(main.get().getRuntimeClasspath());
            task.getJavaLauncher().set(toolchains.launcherFor(spec->spec.getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of))));
            task.doFirst(t->{File run=ext.getRunDirectory().dir("server").get().getAsFile();run.mkdirs();task.setWorkingDir(run);});
        });
        var jar=project.getTasks().named("jar",Jar.class);
        ext.getModifiedJar().convention(jar.flatMap(Jar::getArchiveFile));
        binary(project,ext,"generatePatches","generate").configure(task->task.getModifiedJar().set(ext.getModifiedJar()));
        binary(project,ext,"restoreArchive","restore").configure(task->{
            task.getOutputArchive().set(project.getLayout().getBuildDirectory().file("patchgen/restored.jar"));
        });
    }

    private TaskProvider<BinaryPatchTask> binary(Project p,PatchgenExtension ext,String name,String operation) {
        return p.getTasks().register(name,BinaryPatchTask.class,task->{
            task.setGroup("patchgen");task.getOperation().set(operation);task.getBaseJar().set(ext.getBaseJar());
            task.getInputArchive().set(ext.getInputArchive());task.getOutputArchive().set(ext.getOutputArchive());
            task.getArchivePrefix().set(ext.getArchivePrefix());task.getUnprefixedPackages().set(ext.getUnprefixedPackages());
            task.getAdditionalIncludes().set(ext.getAdditionalIncludes());
        });
    }
}
