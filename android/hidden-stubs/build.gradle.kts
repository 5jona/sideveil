import pochita.sideveil.build.AndroidSdkPaths

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly(files(AndroidSdkPaths.resolveAndroidJar(providers, ":hidden-stubs")))
}
