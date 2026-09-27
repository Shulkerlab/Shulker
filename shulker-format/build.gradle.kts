// The .shulk region file format: storage, compression and dictionaries. No Minecraft classes,
// so it can be unit tested on its own and used by the offline world converter.

dependencies {
    api("com.github.luben:zstd-jni:1.5.7-20")
    compileOnly("org.jspecify:jspecify:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
