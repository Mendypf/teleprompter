import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.mendypf.prompter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mendypf.prompter"
        // 33 = Android 13: the first version that lets an app hand its own sound to the built-in speech AI.
        minSdk = 33
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
