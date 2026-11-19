import java.io.File

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

fun resolveAndroidJar(): File {
    val sdkRoot =
        providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must be set to compile :hidden-stubs")

    val platformsDir = File(sdkRoot, "platforms")
    val preferred = listOf("android-37.2", "android-37.0", "android-37", "android-36")
    for (name in preferred) {
        val candidate = File(platformsDir, "$name/android.jar")
        if (candidate.isFile) {
            return candidate
        }
    }

    return platformsDir
        .listFiles()
        ?.asSequence()
        ?.map { File(it, "android.jar") }
        ?.filter { it.isFile }
        ?.sortedByDescending { it.parentFile.name }
        ?.firstOrNull()
        ?: error("No android.jar found under ${platformsDir.absolutePath}")
}

dependencies {
    compileOnly(files(resolveAndroidJar()))
}
