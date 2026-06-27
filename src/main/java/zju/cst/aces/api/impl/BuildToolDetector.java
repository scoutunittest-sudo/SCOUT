package zju.cst.aces.api.impl;

import java.io.File;

/**
 * Detects the build tool of a target project directory by the presence of its
 * build files. Maven wins when both a pom and Gradle scripts are present.
 */
public final class BuildToolDetector {

    private BuildToolDetector() {
    }

    public enum BuildTool {
        MAVEN,
        GRADLE,
        NONE
    }

    private static final String[] GRADLE_MARKERS = {
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts"
    };

    public static BuildTool detect(File projectDir) {
        if (projectDir == null) {
            return BuildTool.NONE;
        }
        if (new File(projectDir, "pom.xml").isFile()) {
            return BuildTool.MAVEN;
        }
        for (int i = 0; i < GRADLE_MARKERS.length; i++) {
            if (new File(projectDir, GRADLE_MARKERS[i]).isFile()) {
                return BuildTool.GRADLE;
            }
        }
        return BuildTool.NONE;
    }
}
