import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

val moduleProps =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("../module.prop"))
            .asText
            .orNull
            ?.let { load(it.reader()) }
    }

val moduleVersionName: String =
    moduleProps
        .getProperty("version")
        ?.trim()
        ?.removePrefix("v")
        ?.ifEmpty { "0.1.0" } ?: "0.1.0"
val moduleVersionCode: Int =
    moduleProps.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 100

val keystoreProps =
    Properties().apply {
        providers
            .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
            .asText
            .orNull
            ?.let { load(it.reader()) }
    }

val releaseStoreFilePath: String? =
    keystoreProps.getProperty("storeFile")
        ?: providers.environmentVariable("KEYSTORE_FILE").orNull

android {
    namespace = "pochita.sideveil.host"
    compileSdk = 37

    defaultConfig {
        applicationId = "pochita.sideveil.host"
        minSdk = 36
        targetSdk = 37
        versionCode = moduleVersionCode
        versionName = moduleVersionName
    }

    signingConfigs {
        if (!releaseStoreFilePath.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword =
                    keystoreProps.getProperty("storePassword")
                        ?: providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias =
                    keystoreProps.getProperty("keyAlias")
                        ?: providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword =
                    keystoreProps.getProperty("keyPassword")
                        ?: providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val explicitReleaseSigning = signingConfigs.findByName("release")
            val requireReleaseKeystore =
                providers.environmentVariable("REQUIRE_RELEASE_KEYSTORE").orNull == "true" ||
                    providers.environmentVariable("ALLOW_DEBUG_RELEASE_SIGNING").orNull == "false"
            signingConfig =
                if (explicitReleaseSigning != null) {
                    explicitReleaseSigning
                } else if (requireReleaseKeystore) {
                    throw org.gradle.api.GradleException(
                        "Release keystore not configured (keystore.properties or KEYSTORE_FILE required when REQUIRE_RELEASE_KEYSTORE=true).",
                    )
                } else {
                    logger.warn(
                        "WARNING: Release keystore not configured; falling back to debug signing config for local development build.",
                    )
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = false
        aidl = false
        buildConfig = true
        shaders = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/LICENSE*",
                    "/META-INF/NOTICE*",
                )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
