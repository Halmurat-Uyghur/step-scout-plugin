import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("jvm") version "2.4.20"
}

group = "com.stepscout"
version = "1.3.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }   // JetBrains + Marketplace
}

dependencies {
    intellijPlatform {
        // Target IDE; override with -Pstepscout.ideaVersion to build against another release.
        // The IDEA Community Maven artifact is used for compiling and testing: the unified
        // intellijIdea() installer loads Ultimate-only startup activities that cannot be created
        // in headless platform tests. The plugin only uses APIs present in both editions, and
        // verifyPlugin checks the unified IntelliJ IDEA distribution.
        intellijIdeaCommunity(providers.gradleProperty("stepscout.ideaVersion").orElse("2026.2.3")) {
            useInstaller = false
        }

        // Bundled with the IDE
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")

        // Marketplace plugins matching the target IDE build (262.*);
        // override via -Pstepscout.gherkinVersion / -Pstepscout.cucumberJavaVersion
        val gherkinVersion = providers.gradleProperty("stepscout.gherkinVersion").orElse("262.8665.173")
        val cucumberJavaVersion = providers.gradleProperty("stepscout.cucumberJavaVersion").orElse("262.8665.176")
        plugin("gherkin:${gherkinVersion.get()}")
        plugin("cucumber-java:${cucumberJavaVersion.get()}")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Call interface default methods directly instead of generating bridge overrides.
        // Otherwise every ToolWindowFactory default (getAnchor, getIcon, manage, isApplicable, ...)
        // is compiled into our factory and flagged by the Plugin Verifier as an internal,
        // experimental or deprecated API usage.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            // 2025.2 is the oldest release verified against; no upper bound, per current
            // Marketplace guidance. Compatibility with new releases is checked by verifyPlugin.
            sinceBuild = "252"
            untilBuild = provider { null }
        }
        changeNotes = """
            <ul>
              <li><b>Fixed:</b> the StepScout tool window stayed empty when a project was opened (1.2.0 regression).</li>
              <li><b>New:</b> right-click a <code>.feature</code> file and choose <i>Exclude from StepScout</i> to add it to the excluded paths.</li>
              <li><b>New:</b> Refresh and Settings icons in the tool window toolbar; a reset-filters icon replaces the Clear All button.</li>
              <li><b>Changed:</b> stats fit on one line, the split pane uses a thin divider and search waits for a pause in typing.</li>
              <li><b>Changed:</b> the exclude paths text area fills the settings page.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_TOKEN")
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2025.2.6")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}

tasks.runIde {
    maxHeapSize = "2048m"
}

tasks.test {
    useJUnit()
}
