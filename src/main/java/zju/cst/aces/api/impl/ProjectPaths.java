package zju.cst.aces.api.impl;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared filesystem helpers for {@link Project} implementations: jar scanning
 * and locating the chatunitest-core install directory (for its bundled {@code
 * dep/} jars). Extracted from {@code ProjectImpl} so both Maven and Gradle
 * implementations share one copy.
 */
public final class ProjectPaths {

    private ProjectPaths() {
    }

    public static void scanDirectory(File baseDir, File currentDir, List<String> result, String suffix) {
        File[] files = currentDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(baseDir, file, result, suffix);
            } else if (file.isFile() && file.getName().endsWith(suffix)) {
                result.add(file.getAbsolutePath());
            }
        }
    }

    public static List<String> collectJars(String dir) {
        List<String> result = new ArrayList<>();
        if (dir == null) {
            return result;
        }
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) {
            return result;
        }
        scanDirectory(d, d, result, ".jar");
        return result;
    }

    public static List<String> collectCoreDependencyPaths(File coreProjectDir) {
        List<String> result = new ArrayList<>();
        if (coreProjectDir == null) {
            return result;
        }
        File depDir = new File(coreProjectDir, "dep");
        if (!depDir.exists() || !depDir.isDirectory()) {
            return result;
        }
        scanDirectory(depDir, depDir, result, ".jar");
        return result;
    }

    public static File resolveCoreProjectDir() {
        try {
            File location = Paths.get(ProjectPaths.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toFile();
            File dir = location.isFile() ? location.getParentFile() : location;
            if ("classes".equals(dir.getName()) && dir.getParentFile() != null) {
                dir = dir.getParentFile();
            }
            if ("target".equals(dir.getName()) && dir.getParentFile() != null) {
                return dir.getParentFile();
            }
            return dir;
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }
}
