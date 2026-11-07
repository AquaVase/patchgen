package patchgen.gradle;

import java.io.File;
import java.util.List;
import net.minecraftforge.gradle.mcp.MCPPlugin;
import net.minecraftforge.gradle.mcp.MCPExtension;
import net.minecraftforge.gradle.patcher.PatcherPlugin;
import net.minecraftforge.gradle.patcher.PatcherExtension;
import net.minecraftforge.gradle.common.tasks.DownloadAssets;
import net.minecraftforge.gradle.common.tasks.ApplyMappings;
import net.minecraftforge.gradle.common.tasks.ExtractNatives;
import net.minecraftforge.gradle.common.util.Utils;
import net.minecraftforge.gradle.common.util.MavenArtifactDownloader;
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
        ext.getSourcePatchesDirectory().convention(project.getLayout().getProjectDirectory().dir("patches"));
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
        ext.getBaseJar().convention(project.getLayout().file(ext.getMinecraftVersion().map(version->{
            File client=MavenArtifactDownloader.generate(project,"net.minecraft:client:"+version,true);
            if(client==null||!client.isFile())
                throw new GradleException("Cannot resolve Minecraft client JAR for version "+version);
            return client;
        })));
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
        var mappedSources=project.getTasks().named("srg2mcp",ApplyMappings.class).flatMap(ApplyMappings::getOutput);
        project.getTasks().register("genSourcePatches",GenerateSourcePatchesTask.class,task->{
            task.setGroup("patchgen");task.setDescription("Generate unified source patches from user sources against mapped MCP sources.");
            task.getBaseSources().set(mappedSources);
            task.getSourcesDirectory().set(ext.getSourcesDirectory());
            task.getPatchesDirectory().set(ext.getSourcePatchesDirectory());
        });
        project.getTasks().register("restoreSourceFromPatches",RestoreSourceFromPatchesTask.class,task->{
            task.setGroup("patchgen");task.setDescription("Restore patched and added Java sources from mapped MCP sources and source patches.");
            task.getBaseSources().set(mappedSources);
            task.getPatchesDirectory().set(ext.getSourcePatchesDirectory());
            task.getSourcesDirectory().set(ext.getSourcesDirectory());
        });
        var sync=project.getTasks().register("syncSources",SyncSourcesTask.class,task->{
            task.setGroup("patchgen");task.setDescription("Overlay configured sources and resources into this project.");
            task.getSourceOverlays().from(ext.getSourceOverlays());task.getResourceOverlays().from(ext.getResourceOverlays());
            task.getSourcesDirectory().set(ext.getSourcesDirectory());
            task.getResourcesDirectory().set(project.getLayout().getProjectDirectory().dir("src/main/resources"));
        });
        project.getTasks().named("compileJava").configure(task->task.dependsOn(sync));
        project.getTasks().named("processResources").configure(task->task.dependsOn(sync));
        var minecraftLibrary=minecraftLibrary(project,ext,project.getExtensions().getByType(JavaToolchainService.class));
        project.getTasks().named("compileJava").configure(task->task.dependsOn(minecraftLibrary));
        var assets=project.getTasks().named("downloadAssets",DownloadAssets.class);
        var copyAssets=project.getTasks().register("copyAssets",Copy.class,task->{
            task.setGroup("patchgen");task.setDescription("Download and copy Minecraft assets into the run directory.");
            task.dependsOn(assets);task.from(assets.map(DownloadAssets::getOutput));
            task.into(ext.getRunDirectory().dir("assets"));
        });
        project.getTasks().register("setupWorkspace",task->{
            task.setGroup("patchgen");task.setDescription("Build the Minecraft dependency and attached sources, download assets and overlay user sources.");
            task.dependsOn(minecraftLibrary,copyAssets,sync);
        });
        project.getTasks().register("compileGame",task->{task.setGroup("patchgen");task.dependsOn("classes");});
        var natives=project.getTasks().named("extractNatives",ExtractNatives.class);
        var toolchains=project.getExtensions().getByType(JavaToolchainService.class);
        project.getTasks().register("runClient",JavaExec.class,task->{
            task.setGroup("patchgen");task.setDescription("Run the compiled client in this Gradle build.");
            task.dependsOn("classes",copyAssets,natives);
            task.getMainClass().set(ext.getClientMainClass());task.setClasspath(main.get().getRuntimeClasspath());
            task.getJavaLauncher().set(toolchains.launcherFor(spec->spec.getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of))));
            task.doFirst(t->{
                File run=ext.getRunDirectory().get().getAsFile();run.mkdirs();task.setWorkingDir(run);
                VersionJson metadata;
                try { metadata=Utils.loadJson(assets.get().getMeta().get().getAsFile(),VersionJson.class); }
                catch(java.io.IOException e) { throw new GradleException("Cannot read Minecraft asset metadata",e); }
                task.args("--gameDir",run.getAbsolutePath(),"--version",ext.getMinecraftVersion().get(),
                    "--assetsDir",copyAssets.get().getDestinationDir().getAbsolutePath(),"--assetIndex",metadata.assetIndex.id,"--accessToken","0");
                task.systemProperty("java.library.path",natives.get().getOutput().get().getAsFile().getAbsolutePath());
            });
            task.jvmArgs("-XX:StackShadowPages=32");
            if(System.getProperty("os.name").toLowerCase().contains("mac"))task.jvmArgs("-XstartOnFirstThread");
        });
        project.getTasks().register("runServer",JavaExec.class,task->{
            task.setGroup("patchgen");task.setDescription("Run the compiled server with interactive console input.");
            task.dependsOn("classes");task.getMainClass().set(ext.getServerMainClass());
            task.setStandardInput(System.in);
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

    private TaskProvider<Task> minecraftLibrary(Project project,PatchgenExtension ext,JavaToolchainService toolchains) {
        // The joined MCP pipeline inserts OnlyIn/Dist annotations into the merged sources.
        project.getDependencies().add("minecraftImplementation","net.minecraftforge:mergetool:1.1.7:api");
        var mapped=project.getTasks().named("srg2mcp",ApplyMappings.class).flatMap(ApplyMappings::getOutput);
        var sources=mapped.map(file->project.zipTree(file.getAsFile()));
        var compile=project.getTasks().register("compileMinecraftLibrary",JavaCompile.class,task->{
            task.setGroup("patchgen");task.setDescription("Compile mapped MCP sources as a separate Minecraft library.");
            task.source(sources.map(tree->tree.matching(pattern->pattern.include("**/*.java"))));
            task.setClasspath(project.getConfigurations().getByName("minecraftImplementation"));
            task.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("patchgen/minecraft/classes"));
            task.getJavaCompiler().set(toolchains.compilerFor(spec->spec.getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of))));
            task.getOptions().getCompilerArgs().add("-proc:none");
        });
        // A Maven-layout dependency allows Gradle IDE imports to discover the matching sources classifier.
        var repository=project.getLayout().getProjectDirectory().dir(".patchgen/library");
        project.getRepositories().exclusiveContent(content->{
            content.forRepository(()->project.getRepositories().maven(repo->{
                repo.setName("patchgenMinecraftLibrary");repo.setUrl(repository);
                repo.metadataSources(metadata->metadata.artifact());
            }));
            content.filter(filter->filter.includeGroup("patchgen.local"));
        });
        var libraryDir=repository.dir("patchgen/local/minecraft/mapped");
        var libraryJar=project.getTasks().register("minecraftLibraryJar",Jar.class,task->{
            task.setGroup("patchgen");task.getDestinationDirectory().set(libraryDir);
            task.getArchiveFileName().set("minecraft-mapped.jar");
            task.from(compile.flatMap(JavaCompile::getDestinationDirectory));
            task.from(sources,copy->copy.exclude("**/*.java","META-INF/MANIFEST.MF","META-INF/*.SF","META-INF/*.RSA","META-INF/*.DSA"));
        });
        var sourceJar=project.getTasks().register("minecraftLibrarySourcesJar",Jar.class,task->{
            task.setGroup("patchgen");task.getDestinationDirectory().set(libraryDir);
            task.getArchiveFileName().set("minecraft-mapped-sources.jar");task.from(sources);
        });
        project.getDependencies().add("implementation","patchgen.local:minecraft:mapped");
        return project.getTasks().register("prepareMinecraftLibrary",task->{
            task.setGroup("patchgen");task.dependsOn(libraryJar,sourceJar);
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
