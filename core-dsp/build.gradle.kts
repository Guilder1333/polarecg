import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM library: no Android imports allowed here (see CLAUDE.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        // Match the :app module (compileOptions = Java 11) so the phone can use this code.
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
