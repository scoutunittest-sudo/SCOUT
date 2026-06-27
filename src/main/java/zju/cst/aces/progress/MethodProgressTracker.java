package zju.cst.aces.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import zju.cst.aces.api.Logger;
import zju.cst.aces.dto.MethodInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Records every method attempted during test generation as a per-method marker
 * file under a progress directory. Resume decisions are based purely on marker
 * existence (never content), so it is lock-free across threads and unaffected by
 * crash-corrupted content.
 */
public class MethodProgressTracker {

    public static final String PROGRESS_DIR_NAME = "method-progress";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_SANITIZED_LEN = 150;

    private final Path progressDir;
    private final Logger logger;

    public MethodProgressTracker(Path progressDir, Logger logger) {
        this.progressDir = progressDir;
        this.logger = logger;
    }

    /** Roots a tracker at {@code runDirectory/method-progress}. */
    public static MethodProgressTracker forRunDirectory(Path runDirectory, Logger logger) {
        return new MethodProgressTracker(runDirectory.resolve(PROGRESS_DIR_NAME), logger);
    }

    public static String buildKey(String fullClassName, MethodInfo methodInfo) {
        return fullClassName + "#" + methodInfo.getMethodSignature();
    }

    public static String encode(String key) {
        return sanitize(key) + "__" + shortHash(key);
    }

    public boolean shouldSkip(String fullClassName, MethodInfo methodInfo) {
        return Files.exists(progressDir.resolve(encode(buildKey(fullClassName, methodInfo))));
    }

    public void markStarted(String fullClassName, MethodInfo methodInfo, String phase) {
        writeMarker(buildKey(fullClassName, methodInfo), phase, "started", false);
    }

    public void markFinished(String fullClassName, MethodInfo methodInfo, String phase, String status) {
        writeMarker(buildKey(fullClassName, methodInfo), phase, status, true);
    }

    private void writeMarker(String key, String phase, String status, boolean finishing) {
        try {
            Files.createDirectories(progressDir);
            Path file = progressDir.resolve(encode(key));
            String startedAt;
            String finishedAt;
            if (finishing) {
                startedAt = readStartedAt(file);
                finishedAt = now();
            } else {
                startedAt = now();
                finishedAt = null;
            }
            MarkerRecord record = new MarkerRecord(key, phase, startedAt, status, finishedAt);
            Files.write(file, GSON.toJson(record).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            warn("Failed to write progress marker for " + key + ": " + e.getMessage());
        }
    }

    private String readStartedAt(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            MarkerRecord existing = GSON.fromJson(content, MarkerRecord.class);
            return existing == null ? null : existing.startedAt;
        } catch (Exception e) {
            return null;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    static String sanitize(String key) {
        String s = key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.length() > MAX_SANITIZED_LEN) {
            s = s.substring(0, MAX_SANITIZED_LEN);
        }
        return s;
    }

    static String shortHash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warn(message);
        }
    }

    private static class MarkerRecord {
        String key;
        String phase;
        String startedAt;
        String status;
        String finishedAt;

        MarkerRecord(String key, String phase, String startedAt, String status, String finishedAt) {
            this.key = key;
            this.phase = phase;
            this.startedAt = startedAt;
            this.status = status;
            this.finishedAt = finishedAt;
        }
    }
}
