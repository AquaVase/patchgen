package patchgen.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

/** ForgeGradle requires an MCP parent project, not an external Gradle build. */
public class PatchgenSettingsPlugin implements Plugin<Settings> {
    public static final String MCP_PROJECT = ":patchgenMinecraft";

    @Override public void apply(Settings settings) {
        settings.include(MCP_PROJECT);
        settings.project(MCP_PROJECT).setProjectDir(new java.io.File(settings.getSettingsDir(), ".patchgen/minecraft"));
    }
}
