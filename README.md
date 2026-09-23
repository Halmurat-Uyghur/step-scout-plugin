# StepScout IntelliJ Plugin

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![IDEA: 2025.2+](https://img.shields.io/badge/IntelliJ-2025.2%E2%80%932026.2%2B-blue?logo=intellij-idea)
![JDK 21+](https://img.shields.io/badge/JDK-21%2B-4c8c2b?logo=openjdk)
![Kotlin 2.4](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin)

This project provides the base structure for an IntelliJ IDEA plugin that assists teams in locating Cucumber step definitions and detecting missing steps in their framework.

The tool window shows a summary of the total scenarios, step definitions and feature files at the top. Scenario outlines are expanded by the number of example rows. For example:

```
Total Scenarios: 244
Total Steps: 741
Total Features: 46
```

Below the summary is a list of missing steps and a search box for existing steps.

Use the dropdowns above the search box to filter steps by their definition class or by
"screen" (the text before a colon in the first word, e.g. `Login: I tap submit`). Only the
simple class names are shown. Filters are kept when the tool window refreshes.

### What is detected

- Java/Kotlin methods annotated with Cucumber step annotations in any Gherkin language
  (`io.cucumber.java.*` and legacy `cucumber.api.java.*`), including constant values such as
  `@Given(PREFIX + "text")`.
- Kotlin `cucumber-java8` lambdas (`Given("...") { ... }`).
- Cucumber expressions (`{int}`, `{string}`, `{word}`, custom `{types}`, optional `(s)` text,
  `a/b` alternation) and regular expressions (patterns anchored with `^` or `$`).
- Scenario Outline steps are checked against each Examples row.

The tool window does not open automatically; open it from *View ▸ Tool Windows ▸ StepScout*
or *Tools ▸ Search Steps*. Scans run in the background and restart when files change.

## Development

The plugin is written in **Kotlin** and built with Gradle and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) 2.x.

### Prerequisites

- JDK 21
- The provided Gradle wrapper (Gradle 9.7)

The build compiles and tests against IntelliJ IDEA 2026.2.3 and supports 2025.2 and newer
(`since-build` 252, no upper bound). Override the target with `-Pstepscout.ideaVersion=...`,
and the matching Gherkin / Cucumber for Java plugin versions with
`-Pstepscout.gherkinVersion=...` / `-Pstepscout.cucumberJavaVersion=...`.

### Useful Gradle tasks

- `./gradlew build` – builds the plugin and runs unit and platform tests
- `./gradlew verifyPlugin` – runs the IntelliJ Plugin Verifier against IntelliJ IDEA 2025.2 and 2026.2
- `./gradlew runIde` – launches a development instance of IntelliJ IDEA with the plugin

## Configuration

Excluded paths can be listed at the project level. Any feature file or step definition whose
path contains one of the entries is skipped. The paths are persisted to `.idea/stepscout.xml`
and can be edited under *Settings ▸ Tools ▸ StepScout*.

## Maintainer / Contact

- Name: Halmurat Tahir
- Email: halmurat.sdet@gmail.com
- LinkedIn: https://www.linkedin.com/in/halmurat-tahir/

## License

This project is licensed under the terms of the [MIT License](LICENSE).
