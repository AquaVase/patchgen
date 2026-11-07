package patchgen.gradle;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;

public abstract class GenerateSourcePatchesTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getBaseSources();
    // A missing directory is an empty override set, not a request to delete Minecraft classes.
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getSourcesDirectory();
    @OutputDirectory public abstract DirectoryProperty getPatchesDirectory();

    @TaskAction public void generate() throws IOException {
        SourcePatches.generate(getBaseSources().get().getAsFile().toPath(),
            getSourcesDirectory().get().getAsFile().toPath(),getPatchesDirectory().get().getAsFile().toPath());
    }
}
