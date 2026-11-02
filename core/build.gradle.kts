import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin, no Android: the part that follows the voice through the script.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.mendypf.prompter"

// Java 17 code, the same as the app. (No separate Java 17 install is needed: the build's own Java makes it.)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Test libraries only; the engine itself uses nothing but Kotlin.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
