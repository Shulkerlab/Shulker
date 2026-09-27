package dev.shulker.launcher;

/** The subset of JVM introspection the flag planner needs; lets tests fake any JVM build. */
interface VmCapabilities {

    /** Whether the -XX option exists in the target JVM. */
    boolean supports(String option);

    /** Whether a boolean -XX option is already on by default in the target JVM. */
    boolean isEnabledByDefault(String booleanOption);
}
