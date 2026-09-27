package dev.shulker.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes sure the planned flags actually start this JVM before we bet the server on them.
 * Some combinations only fail at startup (flag conflicts, vendor builds, GraalVM), which the
 * per-flag MXBean probe cannot see. The result is cached, so this costs one short JVM start
 * per new flag set.
 */
final class FlagValidator {

    private final Path cacheFile;

    FlagValidator(Path shulkerDir) {
        this.cacheFile = shulkerDir.resolve("validated-flags");
    }

    boolean validate(String java, String javaVersion, List<String> args) {
        final String signature = javaVersion + " " + String.join(" ", args);
        if (cachedSignature().equals(signature)) {
            return true;
        }

        final List<String> command = new ArrayList<>();
        command.add(java);
        for (String arg : args) {
            // Pre-touching and committing the full heap just to print a version is pointless.
            if (arg.startsWith("-Xms") || arg.equals("-XX:+AlwaysPreTouch")) {
                continue;
            }
            command.add(arg);
        }
        command.add("-version");

        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                Log.warn("The JVM rejected the tuned flags:");
                output.lines().limit(10).forEach(line -> Log.warn("  " + line));
                return false;
            }
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, signature);
            return true;
        } catch (IOException e) {
            Log.warn("Could not validate JVM flags: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String cachedSignature() {
        try {
            return Files.readString(cacheFile);
        } catch (IOException e) {
            return "";
        }
    }
}
