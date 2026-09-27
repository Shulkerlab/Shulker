package dev.shulker.launcher;

/** Console output before the server's logger exists. */
final class Log {

    private static final String PREFIX = "[Shulker] ";

    private Log() {
    }

    static void info(String message) {
        System.out.println(PREFIX + message);
    }

    static void warn(String message) {
        System.err.println(PREFIX + "WARN: " + message);
    }

    static String bytes(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(java.util.Locale.ROOT, "%.1f GiB", bytes / (double) (1L << 30));
        }
        return (bytes >> 20) + " MiB";
    }
}
