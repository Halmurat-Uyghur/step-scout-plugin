import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform") version "2.7.2"
    kotlin("jvm") version "2.2.0"
}

group = "com.stepscout"
version = "1.1.4"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }   // JetBrains + Marketplace
}

    dependencies {
        intellijPlatform {
        // Base IDE - 2025.2 to align with cached plugin versions and ensure runIde works
        intellijIdeaCommunity("2025.2")

        // Bundled with the IDE
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        // Marketplace plug-ins (parametrized for easy bumping)
        // Defaults aligned to IDEA 2025.2 (252.*); override via -Pstepscout.gherkinVersion / -Pstepscout.cucumberJavaVersion
        val gherkinVersion = providers.gradleProperty("stepscout.gherkinVersion").orElse("252.23892.201")
        val cucumberJavaVersion = providers.gradleProperty("stepscout.cucumberJavaVersion").orElse("252.23892.248")
        plugin("gherkin:${gherkinVersion.get()}")
        plugin("cucumber-java:${cucumberJavaVersion.get()}")

        // Test framework for IntelliJ Platform - excludes conflicting coroutines dependencies
        testFramework(TestFrameworkType.Platform)
        }
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.9") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
}

kotlin { jvmToolchain(21) }
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}


intellijPlatform {
    buildSearchableOptions.set(false)
    pluginVerification { ides { recommended() } }
//    publishing {
//        token.set(System.getenv("JETBRAINS_TOKEN"))
//    }
}

tasks.runIde {
    // Enable new UI by default with additional optimizations
    jvmArgs(
        "-Dide.experimental.ui=true",
        "-Dide.new.toolbar=true",
        "-Dide.experimental.new.project.view=true"
    )
    // Allocate sufficient heap for the IDE
    maxHeapSize = "2048m"
    // Uncomment for debug logging
    // jvmArgs("-Didea.log.debug=true")

    // Workaround: disable the bundled Gradle plugin in the sandbox to avoid
    // a startup exception in 2024.1 related to parsing newer Java versions
    // in Gradle JVM support matrix (IllegalArgumentException: 25).
    doFirst {
        val configDir = layout.buildDirectory.dir("idea-sandbox/config").get().asFile
        configDir.mkdirs()
        val disabled = configDir.resolve("disabled_plugins.txt")
        // Keep any existing disabled entries and add the Gradle plugin id if missing
        val current = if (disabled.exists()) disabled.readLines() else emptyList()
        val updated = (current + "com.intellij.gradle").toSet().joinToString("\n")
        disabled.writeText(updated)
    }
}

// The runIde task is not compatible with Gradle Configuration Cache.
// Avoid cache storage errors like: cannot serialize DefaultProject.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
    notCompatibleWithConfigurationCache("runIde uses non-serializable Project state")
}

tasks.patchPluginXml {
    version = project.version.toString()
    changeNotes = """
        <ul>
          <li><b>Fixed:</b> Suppressed unavoidable deprecated and experimental API warnings from ToolWindowFactory interface.</li>
          <li><b>Technical:</b> Added @Suppress annotations to handle Kotlin compiler bridge method generation for interface default implementations.</li>
          <li><b>Documentation:</b> Enhanced CLAUDE.md with detailed root cause analysis and solution explanation.</li>
          <li>No functional changes; plugin continues to follow IntelliJ Platform best practices with declarative configuration.</li>
        </ul>
    """.trimIndent()
}

tasks.test {
    useJUnitPlatform()
}
