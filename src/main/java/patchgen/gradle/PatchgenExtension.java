package patchgen.gradle;

import org.gradle.api.file.*;
import org.gradle.api.provider.*;

public abstract class PatchgenExtension {
    public abstract Property<String> getMinecraftVersion();
    public abstract Property<String> getMcpConfig();
    public abstract Property<String> getPipeline();
    public abstract Property<String> getMappingChannel();
    public abstract Property<String> getMappingVersion();
    public abstract Property<Integer> getJavaVersion();
    public abstract DirectoryProperty getSourcesDirectory();
    public abstract DirectoryProperty getSourcePatchesDirectory();
    public abstract DirectoryProperty getRunDirectory();
    public abstract ConfigurableFileCollection getSourceOverlays();
    public abstract ConfigurableFileCollection getResourceOverlays();
    public abstract RegularFileProperty getBaseJar();
    public abstract RegularFileProperty getModifiedJar();
    public abstract RegularFileProperty getInputArchive();
    public abstract RegularFileProperty getOutputArchive();
    public abstract Property<String> getPatchRoot();
    public abstract Property<String> getNotchPrefix();
    public abstract Property<String> getSrgPrefix();
    public abstract RegularFileProperty getSrgBaseJar();
    public abstract RegularFileProperty getNotchModifiedJar();
    public abstract RegularFileProperty getSrgModifiedJar();
    public abstract ListProperty<String> getUnprefixedPackages();
    public abstract ListProperty<String> getAdditionalIncludes();
    public abstract Property<String> getClientMainClass();
    public abstract Property<String> getServerMainClass();
}
