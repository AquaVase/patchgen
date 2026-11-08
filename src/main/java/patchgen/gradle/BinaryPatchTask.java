package patchgen.gradle;

import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

public abstract class BinaryPatchTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getBaseJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getModifiedJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getSrgBaseJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getSrgModifiedJar();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getInputArchive();
    @OutputFile public abstract RegularFileProperty getOutputArchive();
    @Input public abstract Property<String> getOperation();
    @Input public abstract Property<String> getPatchRoot();
    @Input public abstract Property<String> getNotchPrefix();
    @Input public abstract Property<String> getSrgPrefix();
    @Input public abstract ListProperty<String> getUnprefixedPackages();
    @Input public abstract ListProperty<String> getAdditionalIncludes();

    @TaskAction public void executePatch() throws Exception {
        var layout=PatchLayout.of(getPatchRoot().get(),getNotchPrefix().get(),getSrgPrefix().get());
        var base=ArchiveIO.read(getBaseJar().get().getAsFile().toPath());
        boolean srg=!layout.srg().isEmpty();
        ArchiveIO.require(!srg||getSrgBaseJar().isPresent(),"srgBaseJar is required when srgPrefix is configured");
        var srgBase=srg?ArchiveIO.read(getSrgBaseJar().get().getAsFile().toPath()):Map.<String,byte[]>of();
        var output=getOutputArchive().get().getAsFile().toPath();
        if(getOperation().get().equals("restore")) {
            var entries=ArchivePatches.restore(base,srgBase,ArchiveIO.read(getInputArchive().get().getAsFile().toPath()),layout);
            ArchiveIO.write(output,entries);
        } else {
            ArchiveIO.require(!srg||getSrgModifiedJar().isPresent(),"srgModifiedJar is required when srgPrefix is configured");
            var target=ArchivePatches.target(base,srgBase,ArchiveIO.read(getModifiedJar().get().getAsFile().toPath()),
                srg?ArchiveIO.read(getSrgModifiedJar().get().getAsFile().toPath()):Map.of(),
                getInputArchive().isPresent()?ArchiveIO.read(getInputArchive().get().getAsFile().toPath()):null,
                layout,getUnprefixedPackages().get(),getAdditionalIncludes().get());
            ArchivePatches.generate(base,srgBase,target,output,layout);
            getLogger().lifecycle("patchgen: {} target entries encoded and roundtrip verified: {}",target.size(),output);
        }
    }
}
