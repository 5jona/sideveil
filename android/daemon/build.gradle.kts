import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

fun resolveSdkRoot(): File {
    val sdkPath =
        providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must be set to build :daemon")
    return File(sdkPath)
}

fun resolveAndroidJar(sdkRoot: File): File {
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

fun resolveD8(sdkRoot: File): File {
    val buildToolsDir = File(sdkRoot, "build-tools")
    return buildToolsDir
        .listFiles()
        ?.asSequence()
        ?.sortedByDescending { it.name }
        ?.map { File(it, "d8") }
        ?.firstOrNull { it.isFile && it.canExecute() }
        ?: error("No executable d8 found under ${buildToolsDir.absolutePath}")
}

dependencies {
    implementation(project(":shared"))
    compileOnly(project(":hidden-stubs"))
    compileOnly(files(resolveAndroidJar(resolveSdkRoot())))
    testCompileOnly(project(":hidden-stubs"))
    testCompileOnly(files(resolveAndroidJar(resolveSdkRoot())))
    testImplementation(libs.junit)
}

abstract class BuildDaemonDexTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val daemonJar: RegularFileProperty

    @get:Classpath
    abstract val runtimeJars: ConfigurableFileCollection

    @get:InputFile
    abstract val androidJar: RegularFileProperty

    @get:InputFile
    abstract val d8Executable: RegularFileProperty

    @get:OutputFile
    abstract val outputDex: RegularFileProperty

    @TaskAction
    fun run() {
        val tempDexDir = temporaryDir.apply { mkdirs() }
        tempDexDir.listFiles()?.forEach { it.deleteRecursively() }

        val inputArchives =
            buildList {
                add(daemonJar.get().asFile.absolutePath)
                runtimeJars.files
                    .filter { it.isFile && (it.extension == "jar" || it.extension == "class") }
                    .forEach { add(it.absolutePath) }
            }

        execOperations.exec {
            executable = d8Executable.get().asFile.absolutePath
            args("--min-api", "36")
            args("--lib", androidJar.get().asFile.absolutePath)
            args("--output", tempDexDir.absolutePath)
            args(inputArchives)
        }

        val compiledDex = File(tempDexDir, "classes.dex")
        require(compiledDex.isFile) { "d8 did not produce classes.dex in ${tempDexDir.absolutePath}" }
        val extraDex = File(tempDexDir, "classes2.dex")
        require(!extraDex.exists()) { "Multi-dex output unexpected for daemon (${extraDex.name})" }

        val target = outputDex.get().asFile
        target.parentFile.mkdirs()
        compiledDex.copyTo(target, overwrite = true)
    }
}

val sdkRootDir = resolveSdkRoot()
val androidPlatformJar = resolveAndroidJar(sdkRootDir)
val d8Bin = resolveD8(sdkRootDir)

tasks.register<BuildDaemonDexTask>("buildDex") {
    dependsOn(tasks.named("jar"))
    daemonJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeJars.from(configurations.runtimeClasspath)
    androidJar.set(androidPlatformJar)
    d8Executable.set(d8Bin)
    outputDex.set(rootProject.layout.projectDirectory.file("../bin/sideveil-daemon.dex"))
}
