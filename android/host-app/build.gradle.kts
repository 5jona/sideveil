import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

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
        minSdk = 31
        targetSdk = 37
        versionCode = 100
        versionName = "0.1.0"
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
            signingConfig =
                signingConfigs.findByName("release")
                    ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = false
        aidl = false
        buildConfig = false
        shaders = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
