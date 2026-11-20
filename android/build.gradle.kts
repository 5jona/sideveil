plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.versions)
    alias(libs.plugins.spotless)
}

subprojects {
    plugins.withId("dev.detekt") {
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
            buildUponDefaultConfig.set(true)
            parallel.set(true)
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            val baselineFile = project.file("detekt-baseline.xml")
            if (baselineFile.exists()) {
                baseline.set(baselineFile)
            }
        }
    }
}

spotless {
    val ktlintOverrides =
        mapOf(
            "ktlint_code_style" to "ktlint_official",
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            "ktlint_standard_filename" to "disabled",
        )
    kotlin {
        target("protocol/src/**/*.kt", "sensor/src/**/*.kt", "host-app/src/**/*.kt")
        ktlint()
            .setEditorConfigPath(rootProject.file("../.editorconfig"))
            .editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "buildSrc/*.gradle.kts",
            "protocol/*.gradle.kts",
            "sensor/*.gradle.kts",
            "host-app/*.gradle.kts",
            "hidden-stubs/*.gradle.kts",
        )
        ktlint()
            .setEditorConfigPath(rootProject.file("../.editorconfig"))
            .editorConfigOverride(ktlintOverrides)
    }
    format("misc") {
        target(
            "gradle/*.toml",
            "config/detekt/*.yml",
            "../.gitignore",
            "../.editorconfig",
            "../mise.toml",
            "../README.md",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
