import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.intellij.platform") version "2.19.0"
    kotlin("jvm") version "2.4.20"
}

group = "com.stepscout"
version = "1.2.0"

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
              <li><b>Compatibility:</b> IntelliJ IDEA 2025.2 through 2026.2 and later.</li>
              <li><b>Fixed:</b> a single step using optional text such as <code>cucumber(s)</code> no longer hides every step definition.</li>
              <li><b>Fixed:</b> Cucumber expressions now match like Cucumber: typed parameters, optional text, alternation and escapes; <code>/regex/</code> patterns are supported.</li>
              <li><b>Fixed:</b> Scenario Outline steps are checked against their Examples values instead of being reported missing.</li>
              <li><b>Fixed:</b> Kotlin regex step definitions, constant annotation values and non-English step annotations are recognised.</li>
              <li><b>Fixed:</b> Background blocks are no longer counted as scenarios; filters survive refreshes; library steps can be opened.</li>
              <li><b>Fixed:</b> searching for text with punctuation such as <code>log-in</code> or <code>{int}</code> now finds steps.</li>
              <li><b>Performance:</b> cached, cancellable background scans that no longer block typing or rescan on unrelated file changes.</li>
              <li><b>Changed:</b> the tool window no longer opens automatically in every project; settings moved to Tools &gt; StepScout.</li>
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
