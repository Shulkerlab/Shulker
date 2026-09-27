package dev.shulker.launcher;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.lang.management.ManagementFactory;
import java.util.Optional;

/**
 * Asks the running JVM which -XX options it knows about. The launcher and the server run on
 * the same java binary, so this tells us - for free, without spawning a process - whether a
 * collector or feature was compiled into this build (e.g. Oracle JDK ships without Shenandoah).
 */
final class VmOptions implements VmCapabilities {

    private final HotSpotDiagnosticMXBean diagnostics;

    VmOptions() {
        HotSpotDiagnosticMXBean bean;
        try {
            bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        } catch (RuntimeException | LinkageError e) {
            bean = null; // non-HotSpot VM
        }
        this.diagnostics = bean;
    }

    /** Whether the option exists in this VM. Unknown when not running on HotSpot, so assume yes. */
    @Override
    public boolean supports(String name) {
        return diagnostics == null || value(name).isPresent();
    }

    Optional<String> value(String name) {
        if (diagnostics == null) {
            return Optional.empty();
        }
        try {
            final VMOption option = diagnostics.getVMOption(name);
            return Optional.of(option.getValue());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isEnabledByDefault(String booleanFlag) {
        return value(booleanFlag).map(Boolean::parseBoolean).orElse(false);
    }
}
