// The Shulker launcher: detects the host, plans JVM flags and hands off to Paperclip.
// No runtime dependencies on purpose - it runs before anything else is on the classpath.

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks.test {
    useJUnitPlatform()
}
