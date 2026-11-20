import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import pochita.sideveil.build.AndroidSdkPaths
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
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

val androidPlatformJar = AndroidSdkPaths.resolveAndroidJar(providers, ":sensor")
val d8Bin = AndroidSdkPaths.resolveD8(providers, ":sensor")

dependencies {
    implementation(project(":protocol"))
    compileOnly(project(":hidden-stubs"))
    compileOnly(files(androidPlatformJar))
    testCompileOnly(project(":hidden-stubs"))
    testCompileOnly(files(androidPlatformJar))
    testImplementation(libs.junit)
}

abstract class BuildSensorDexTask : DefaultTask() {
    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFile
    abstract val sensorJar: RegularFileProperty

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
                add(sensorJar.get().asFile.absolutePath)
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
        require(!extraDex.exists()) { "Multi-dex output unexpected for sensor (${extraDex.name})" }

        val target = outputDex.get().asFile
        target.parentFile.mkdirs()
        compiledDex.copyTo(target, overwrite = true)
    }
}

tasks.register<BuildSensorDexTask>("buildDex") {
    dependsOn(tasks.named("jar"))
    sensorJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    runtimeJars.from(configurations.runtimeClasspath)
    androidJar.set(androidPlatformJar)
    d8Executable.set(d8Bin)
    outputDex.set(rootProject.layout.projectDirectory.file("../bin/sideveil-sensor.dex"))
}
