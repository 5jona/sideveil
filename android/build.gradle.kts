plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.versions)
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintOverrides =
        mapOf(
            "ktlint_code_style" to "ktlint_official",
            "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
            "ktlint_standard_filename" to "disabled",
        )
    kotlin {
        target("shared/src/**/*.kt", "daemon/src/**/*.kt", "host-app/src/**/*.kt")
        ktlint()
            .setEditorConfigPath(rootProject.file("../.editorconfig"))
            .editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts", "shared/*.gradle.kts", "daemon/*.gradle.kts", "host-app/*.gradle.kts", "hidden-stubs/*.gradle.kts")
        ktlint()
            .setEditorConfigPath(rootProject.file("../.editorconfig"))
            .editorConfigOverride(ktlintOverrides)
    }
    format("misc") {
        target("gradle/*.toml", "../.gitignore", "../.editorconfig", "../mise.toml", "../README.md")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
