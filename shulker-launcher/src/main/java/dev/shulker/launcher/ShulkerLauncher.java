package dev.shulker.launcher;

import dev.shulker.launcher.JvmPlanner.Plan;
import dev.shulker.launcher.LauncherConfig.Collector;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Entry point of {@code shulker.jar}. Detects the host, plans JVM flags for the selected
 * profile and restarts itself as the actual server JVM. Users only ever run
 * {@code java -jar shulker.jar}; the flags they would otherwise copy from a wiki are computed here.
 */
public final class ShulkerLauncher {

    private static final String PAPERCLIP_MAIN = "io.papermc.paperclip.Main";

    private ShulkerLauncher() {
    }

    public static void main(String[] args) throws Exception {
        final Path workDir = Path.of("").toAbsolutePath();
        final Path jar = ownJar();
        if (jar != null && Arrays.stream(args).anyMatch(a -> a.startsWith("--convert-world="))) {
            System.exit(convertWorld(args, workDir, jar));
        }
        LauncherConfig config = LauncherConfig.load(workDir, args);

        if (!config.managed() || jar == null) {
            if (config.managed()) {
                Log.warn("Not running from a jar, starting without tuning");
            }
            handOffToPaperclip(config.serverArgs());
            return;
        }

        config = applyCommandLineIntent(config);
        final HostInfo host = HostInfo.detect();
        final Plan plan = new JvmPlanner(host, config, new VmOptions()).plan();
        printSummary(config, host, plan);

        final String java = javaExecutable();
        final ServerBundle bundle = ServerBundle.read(jar, workDir);
        final List<String> passthrough = passthroughArgs();

        if (config.dryRun()) {
            final List<String> command = new ArrayList<>(List.of(java));
            command.addAll(plan.jvmArgs());
            command.addAll(passthrough);
            command.addAll(List.of("-jar", ".shulker/boot.jar"));
            command.addAll(config.serverArgs());
            Log.info("Dry run, would execute:");
            System.out.println(String.join(" ", command));
            return;
        }

        final Path shulkerDir = workDir.resolve(".shulker");
        final Path marker = shulkerDir.resolve("extracted");
        if (!bundle.isExtracted(marker)) {
            bundle.extract(java, marker);
        }
        final Path bootJar = bundle.writeBootJar(shulkerDir.resolve("boot.jar"));

        List<String> jvmArgs = plan.jvmArgs();
        if (!new FlagValidator(shulkerDir).validate(java, host.javaVersion(), jvmArgs)) {
            jvmArgs = safeArgs(plan, config);
            Log.warn("Falling back to safe flags: " + String.join(" ", jvmArgs));
        }

        final List<String> command = new ArrayList<>();
        command.add(java);
        command.addAll(jvmArgs);
        if (config.aotCache()) {
            final List<String> keyArgs = aotRelevant(jvmArgs);
            final boolean flightRecording = jvmArgs.stream().anyMatch(a -> a.startsWith("-XX:StartFlightRecording"));
            command.addAll(new AotCache(shulkerDir, host, bundle, keyArgs).prepare(java, keyArgs, bootJar, workDir, flightRecording));
        }
        command.addAll(passthrough);
        command.add("-jar");
        command.add(workDir.relativize(bootJar).toString());
        command.addAll(config.serverArgs());

        System.exit(runServer(command, workDir));
    }

    // ---- world conversion ------------------------------------------------------------------

    /**
     * {@code --convert-world=<dir> --to=shulk|mca}: converts a stopped world's region files
     * with the server's own classes, then exits.
     */
    private static int convertWorld(final String[] args, final Path workDir, final Path jar) throws Exception {
        String world = null;
        String target = "shulk";
        for (String arg : args) {
            if (arg.startsWith("--convert-world=")) {
                world = arg.substring("--convert-world=".length());
            } else if (arg.startsWith("--to=")) {
                target = arg.substring("--to=".length());
            }
        }
        if (world == null || world.isBlank() || !(target.equals("shulk") || target.equals("mca"))) {
            Log.warn("Usage: java -jar shulker.jar --convert-world=<world folder> --to=shulk|mca");
            return 2;
        }
        final ServerBundle bundle = ServerBundle.read(jar, workDir);
        final Path shulkerDir = workDir.resolve(".shulker");
        final Path marker = shulkerDir.resolve("extracted");
        final String java = javaExecutable();
        if (!bundle.isExtracted(marker)) {
            bundle.extract(java, marker);
        }
        final Path bootJar = bundle.writeBootJar(shulkerDir.resolve("boot.jar"));
        Log.info("Converting " + world + " to ." + target + " (the server must be stopped)");
        return new ProcessBuilder(java, "-Xmx2G", "--enable-native-access=ALL-UNNAMED",
            "-cp", workDir.relativize(bootJar).toString(), "dev.shulker.format.WorldConverter", world, target)
            .directory(workDir.toFile())
            .inheritIO()
            .start()
            .waitFor();
    }

    // ---- server process --------------------------------------------------------------------

    private static int runServer(List<String> command, Path workDir) throws IOException, InterruptedException {
        final Process server = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .inheritIO()
            .start();

        // `docker stop` and panels signal only us (PID 1). Forward it so the server saves and exits.
        // On Windows destroy() is a hard kill, and Ctrl+C already reaches the server directly.
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server.isAlive()) {
                if (!windows) {
                    server.destroy();
                }
                try {
                    server.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Shulker-Launcher-Shutdown"));

        return server.waitFor();
    }

    private static void handOffToPaperclip(List<String> serverArgs) throws Exception {
        Class.forName(PAPERCLIP_MAIN)
            .getMethod("main", String[].class)
            .invoke(null, (Object) serverArgs.toArray(String[]::new));
    }

    // ---- inputs ----------------------------------------------------------------------------

    /**
     * People (and panel eggs) start us with flags like {@code -Xmx8G}. Treat an explicit heap or
     * collector as intent when the config leaves that choice on auto; ignore the rest, since the
     * tuned set replaces it.
     */
    private static LauncherConfig applyCommandLineIntent(LauncherConfig config) {
        long heap = config.heapOverride();
        Collector collector = config.collector();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xmx") && heap == LauncherConfig.AUTO) {
                heap = LauncherConfig.parseSize(arg.substring(4));
                Log.info("Using heap size from command line: " + arg);
            } else if (collector == Collector.AUTO) {
                collector = switch (arg) {
                    case "-XX:+UseG1GC" -> Collector.G1;
                    case "-XX:+UseZGC" -> Collector.ZGC;
                    case "-XX:+UseShenandoahGC" -> Collector.SHENANDOAH;
                    default -> collector;
                };
            }
        }
        return new LauncherConfig(config.profile(), heap, collector, config.aotCache(), config.managed(),
            config.dryRun(), config.extraJvmArgs(), config.serverArgs());
    }

    /** System properties and agents given to the launcher belong to the server. */
    private static List<String> passthroughArgs() {
        final List<String> args = new ArrayList<>();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-D") || arg.startsWith("-javaagent:")) {
                args.add(arg);
            }
        }
        return args;
    }

    /**
     * Options that must be identical when the AOT cache is created and used: VM flags, and
     * module options (they decide whether the archived module graph can be used).
     */
    private static List<String> aotRelevant(List<String> jvmArgs) {
        return jvmArgs.stream()
            .filter(a -> a.startsWith("-XX:") || a.startsWith("--"))
            .filter(a -> !a.equals("-XX:+AlwaysPreTouch") && !a.startsWith("-XX:SoftMaxHeapSize"))
            .toList();
    }

    private static List<String> safeArgs(Plan plan, LauncherConfig config) {
        final List<String> args = new ArrayList<>();
        args.add("-Xms" + (plan.initialHeap() >> 20) + "M");
        args.add("-Xmx" + (plan.heap() >> 20) + "M");
        args.add("-XX:+UseG1GC");
        args.add("-Dshulker.profile=" + config.profile().id());
        args.add("-Dshulker.launcher=managed");
        return args;
    }

    private static Path ownJar() {
        try {
            final Path path = Path.of(ShulkerLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static void printSummary(LauncherConfig config, HostInfo host, Plan plan) {
        final String environment = host.panel() != null ? host.panel()
            : host.container() ? "container" : "bare metal";
        Log.info("Profile: " + config.profile().id());
        Log.info(String.format(Locale.ROOT, "Host: %d CPUs, %s RAM%s, %s",
            host.cpus(), Log.bytes(host.totalMemory()),
            host.container() ? "" : " (" + Log.bytes(host.availableMemory()) + " free)",
            environment));
        Log.info("Java: " + host.javaVersion() + " (" + host.vmName() + ")");
        Log.info(String.format(Locale.ROOT, "Heap: %s%s | GC: %s",
            Log.bytes(plan.heap()),
            plan.initialHeap() == plan.heap() ? " fixed" : " elastic from " + Log.bytes(plan.initialHeap()),
            plan.collector().name().toLowerCase(Locale.ROOT)));
        for (String note : plan.notes()) {
            Log.info("Note: " + note);
        }
        if (host.javaFeature() < 26) {
            Log.info("Tip: Java 26+ adds faster G1 (JEP 522) and AOT caching with any GC (JEP 516)");
        }
    }
}
