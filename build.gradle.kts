import io.papermc.paperweight.checkstyle.PaperCheckstyleExt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperCommit")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("shulker-server/build.gradle.kts")
            patchFile = file("shulker-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("shulker-api/build.gradle.kts")
            patchFile = file("shulker-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-checkstyle/build.gradle.kts"
            outputFile = file("shulker-checkstyle/build.gradle.kts")
            patchFile = file("shulker-checkstyle/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("shulker-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("shulker-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchDir("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("shulker-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

subprojects {
    apply {
        plugin("java-library")
        plugin("maven-publish")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    val tempDisabled = setOf("shulker-server", "shulker-launcher", "test-plugin")

    if (name !in tempDisabled) {
        apply { plugin("io.papermc.paperweight.paper-checkstyle") }
        extensions.configure<PaperCheckstyleExt> {
            typeUseAnnotationsFile.set(rootProject.layout.projectDirectory.file(".checkstyle/type_use_annotations.txt"))
        }

        dependencies {
            "checkstyle"(project(":shulker-checkstyle"))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test>().configureEach {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }
}

// Final distributable: the Paperclip bundler with the Shulker launcher as its entry point.
// The launcher detects the host, picks JVM flags and then hands off to Paperclip.
val createShulkerJar by tasks.registering(Jar::class) {
    group = "shulker"
    description = "Builds the distributable Shulker jar (Paperclip + Shulker launcher)"

    val paperclip = project(":shulker-server").tasks.named("createPaperclipJar")
    val launcherJar = project(":shulker-launcher").tasks.named<Jar>("jar")
    dependsOn(paperclip, launcherJar)

    archiveFileName = providers.gradleProperty("mcVersion").map { "shulker-$it.jar" }
    destinationDirectory = layout.buildDirectory.dir("libs")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    // Launcher classes first so its manifest wins.
    from(zipTree(launcherJar.flatMap { it.archiveFile }))
    from(zipTree(paperclip.map { it.outputs.files.singleFile })) {
        exclude("META-INF/MANIFEST.MF")
    }
    manifest {
        attributes(
            "Main-Class" to "dev.shulker.launcher.ShulkerLauncher",
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}
