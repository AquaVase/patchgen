package patchgen.gradle;

import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

public abstract class BinaryPatchTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getBaseJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getModifiedJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getInputArchive();
    @OutputFile public abstract RegularFileProperty getOutputArchive();
    @Input public abstract Property<String> getOperation();
    @Input public abstract Property<String> getArchivePrefix();
    @Input public abstract ListProperty<String> getUnprefixedPackages();
    @Input public abstract ListProperty<String> getAdditionalIncludes();

    @TaskAction public void executePatch() throws Exception {
        var base=getBaseJar().get().getAsFile().toPath(); var output=getOutputArchive().get().getAsFile().toPath();
        if(getOperation().get().equals("restore")) {
            var entries=ArchivePatches.restore(base,getInputArchive().get().getAsFile().toPath(),getArchivePrefix().get());
            ArchiveIO.write(output,entries);
        } else {
            var target=ArchivePatches.target(base,getModifiedJar().get().getAsFile().toPath(),
                getInputArchive().isPresent()?getInputArchive().get().getAsFile().toPath():null,
                getArchivePrefix().get(),getUnprefixedPackages().get(),getAdditionalIncludes().get());
            ArchivePatches.generate(base,target,output,getArchivePrefix().get());
            getLogger().lifecycle("patchgen: {} target entries encoded and roundtrip verified: {}",target.size(),output);
        }
    }
}
