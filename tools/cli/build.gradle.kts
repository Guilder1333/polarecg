import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Console tool that runs :core-dsp on recordings and writes CSV. Runs on the PC, not the phone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":core-dsp"))
}

application {
    mainClass = "com.punkunpun.polarisecg.cli.MainKt"
}

// `./gradlew :tools:cli:run` starts in tools/cli by default; relative data/ paths must mean the
// repository's data/ folder.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
