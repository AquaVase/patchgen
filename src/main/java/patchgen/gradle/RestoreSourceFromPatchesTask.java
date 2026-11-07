package patchgen.gradle;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.*;
import org.gradle.api.tasks.*;

public abstract class RestoreSourceFromPatchesTask extends DefaultTask {
    public RestoreSourceFromPatchesTask() {
        // This explicit editing operation must notice manual edits to restored files.
        getOutputs().upToDateWhen(task->false);
    }
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getBaseSources();
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) public abstract DirectoryProperty getPatchesDirectory();
    @OutputDirectory public abstract DirectoryProperty getSourcesDirectory();

    @TaskAction public void restore() throws IOException {
        SourcePatches.restore(getBaseSources().get().getAsFile().toPath(),
            getPatchesDirectory().get().getAsFile().toPath(),getSourcesDirectory().get().getAsFile().toPath());
    }
}
