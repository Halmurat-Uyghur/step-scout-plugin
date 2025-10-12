# StepScout IntelliJ Plugin

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![IDEA: 2025.2](https://img.shields.io/badge/IntelliJ-2025.2-blue?logo=intellij-idea)
![JDK 21+](https://img.shields.io/badge/JDK-21%2B-4c8c2b?logo=openjdk)
![Kotlin 2.2](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin)

This project provides the base structure for an IntelliJ IDEA plugin that assists teams in locating Cucumber step definitions and detecting missing steps in their framework.

The tool window shows a summary of the total scenarios, step definitions and feature files at the top. Scenario outlines are expanded by the number of example rows. For example:

```
Total Scenarios: 244
Total Steps: 741
Total Features: 46
```

Below the summary is a list of missing steps and a search box for existing steps.

Use the dropdown next to the search box to filter steps by their definition class.
Only the simple class names are shown and the dropdown width matches the search field.
The number of steps in the selected class is shown above the results.

## Development

The plugin is written primarily in **Kotlin** with supporting utilities in **Java**. It uses Gradle with the `org.jetbrains.intellij.platform` plugin.

### Prerequisites

- JDK 21
- IntelliJ IDEA 2025.1 (Community Edition is sufficient)
- Gradle 8.14 or the provided wrapper.

If `gradle/wrapper/gradle-wrapper.jar` is missing, generate it by running `gradle wrapper` once.

### Useful Gradle tasks

- `./gradlew build` – builds the plugin
- `./gradlew runIde` – launches a development instance of IntelliJ IDEA with the plugin

## Configuration

Excluded feature files can be listed at the project level. The paths are persisted
to `.idea/stepscout.xml` and can be edited via the **StepScout** configurable
under *File ▸ Settings* (or *Preferences* on macOS).

## Publishing

Set the `JETBRAINS_TOKEN` environment variable to a JetBrains Marketplace API token
and run:

```
./gradlew publishPlugin
```

This repository includes a GitHub Actions workflow that publishes the plugin
automatically whenever a tag starting with `v` is pushed.

## Maintainer / Contact

- Name: Halmurat Tahir
- Email: halmurat.sdet@gmail.com
- LinkedIn: https://www.linkedin.com/in/halmurat-tahir/

## License

This project is licensed under the terms of the [MIT License](LICENSE).
