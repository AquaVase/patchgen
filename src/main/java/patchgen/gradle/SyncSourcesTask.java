package patchgen.gradle;

import java.io.*;
import java.nio.file.*;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;

/** Copy user overlays without modifying the Minecraft dependency. */
public abstract class SyncSourcesTask extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getSourceOverlays();
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract ConfigurableFileCollection getResourceOverlays();
    @Internal public abstract DirectoryProperty getSourcesDirectory();
    @Internal public abstract DirectoryProperty getResourcesDirectory();

    @TaskAction public void overlay() throws IOException {
        copy(getSourceOverlays(),getSourcesDirectory().get().getAsFile().toPath(),true);
        copy(getResourceOverlays(),getResourcesDirectory().get().getAsFile().toPath(),false);
    }

    private void copy(ConfigurableFileCollection overlays,Path destination,boolean javaOnly) throws IOException {
        for(File directory:overlays) {
            ArchiveIO.require(directory.isDirectory(),"Overlay is not a directory: "+directory);
            Path source=directory.toPath();
            try(var files=Files.walk(source)) {
                for(Path file:files.filter(Files::isRegularFile).toList()) {
                    if(javaOnly&&!file.toString().endsWith(".java"))continue;
                    Path target=destination.resolve(source.relativize(file));Files.createDirectories(target.getParent());
                    // Avoid changing timestamps on every compile.
                    if(!Files.exists(target)||Files.mismatch(file,target)!=-1)Files.copy(file,target,StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
