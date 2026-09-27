package dev.shulker.launcher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * The Paperclip payload inside the Shulker jar. Paperclip normally loads the server through its
 * own URLClassLoader; we instead let it extract/patch files and then start the server on the
 * application class path (via {@link #writeBootJar}). That is what makes the Leyden AOT cache
 * cover the server's classes - it only stores classes loaded by the built-in class loaders.
 */
final class ServerBundle {

    private static final long FIXED_TIMESTAMP = 315532800000L; // 1980-01-01, earliest zip time

    private final Path jar;
    private final Path workDir;
    private final List<Path> classpath;
    private final String mainClass;

    private ServerBundle(Path jar, Path workDir, List<Path> classpath, String mainClass) {
        this.jar = jar;
        this.workDir = workDir;
        this.classpath = classpath;
        this.mainClass = mainClass;
    }

    static ServerBundle read(Path jar, Path workDir) throws IOException {
        final List<Path> classpath = new ArrayList<>();
        // versions/ must precede libraries/: the server jar overrides some library classes.
        for (String entry : resourceLines("/META-INF/versions.list")) {
            classpath.add(workDir.resolve("versions").resolve(filePath(entry)));
        }
        for (String entry : resourceLines("/META-INF/libraries.list")) {
            classpath.add(workDir.resolve("libraries").resolve(filePath(entry)));
        }
        final List<String> main = resourceLines("/META-INF/main-class");
        if (classpath.isEmpty() || main.isEmpty()) {
            throw new IOException("Shulker jar is missing its Paperclip metadata");
        }
        return new ServerBundle(jar, workDir, List.copyOf(classpath), main.getFirst().trim());
    }

    List<Path> classpath() {
        return classpath;
    }

    String mainClass() {
        return mainClass;
    }

    /**
     * Writes a tiny jar whose manifest puts the whole server on the class path. Starting the
     * server with {@code -jar boot.jar} (rather than {@code -cp a.jar:b.jar...}) lets the
     * manifest grant native access and final field mutation to plugins. The equivalent
     * command line options would disable the AOT cache's archived module graph.
     *
     * <p>The file is only rewritten when its content changes: the AOT cache is keyed on the
     * class path's timestamps.
     */
    Path writeBootJar(Path bootJar) throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        attributes.put(Attributes.Name.CLASS_PATH, String.join(" ", classpath.stream()
            .map(p -> relativeUrl(bootJar.getParent(), p))
            .toList()));
        attributes.putValue("Enable-Native-Access", "ALL-UNNAMED");
        // JEP 500 (Java 26+); older JVMs ignore unknown attributes.
        attributes.putValue("Enable-Final-Field-Mutation", "ALL-UNNAMED");

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            final ZipEntry entry = new ZipEntry(JarFile.MANIFEST_NAME);
            entry.setTime(FIXED_TIMESTAMP); // byte-identical output for identical input
            jar.putNextEntry(entry);
            manifest.write(jar);
            jar.closeEntry();
            jar.setComment("Shulker boot jar - generated, do not edit");
        }
        final byte[] content = bytes.toByteArray();
        if (!Files.isRegularFile(bootJar) || !Arrays.equals(Files.readAllBytes(bootJar), content)) {
            Files.createDirectories(bootJar.getParent());
            Files.write(bootJar, content);
        }
        return bootJar;
    }

    /** Class-Path entries are relative URLs, so spaces and the like must be escaped. */
    private static String relativeUrl(Path from, Path to) {
        final StringBuilder url = new StringBuilder();
        for (Path segment : from.relativize(to)) {
            if (!url.isEmpty()) {
                url.append('/');
            }
            url.append(URLEncoder.encode(segment.toString(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return url.toString();
    }

    /** True when Paperclip has already extracted everything for this exact jar. */
    boolean isExtracted(Path marker) {
        if (!Files.exists(marker)) {
            return false;
        }
        try {
            if (!Files.readString(marker).trim().equals(jarFingerprint())) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        return classpath.stream().allMatch(Files::isRegularFile);
    }

    /**
     * Runs Paperclip in patch-only mode in a small throwaway JVM: downloads the Mojang jar,
     * applies Shulker's patches and extracts libraries, then exits.
     */
    void extract(String javaExecutable, Path marker) throws IOException, InterruptedException {
        Log.info("Preparing server files (first start or new version)...");
        final Process process = new ProcessBuilder(
            javaExecutable, "-Xmx512M", "-Dpaperclip.patchonly=true",
            "-cp", jar.toString(), "io.papermc.paperclip.Main")
            .directory(workDir.toFile())
            .inheritIO()
            .start();
        final int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Paperclip failed to prepare the server (exit code " + exit + ")");
        }
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, jarFingerprint());
    }

    String jarFingerprint() throws IOException {
        return Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
    }

    /** Lines look like {@code <sha256>\t<id>\t<path>}. */
    private static String filePath(String entry) {
        final String[] parts = entry.split("\t");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Malformed Paperclip entry: " + entry);
        }
        return parts[2];
    }

    private static List<String> resourceLines(String name) throws IOException {
        final InputStream stream = ServerBundle.class.getResourceAsStream(name);
        if (stream == null) {
            return List.of();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(l -> !l.isBlank()).toList();
        }
    }
}
