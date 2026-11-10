import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

// The release key lives outside the repo: in ~/.prompter, or wherever -Pprompter.keystore or PROMPTER_KEYSTORE
// points, with its passwords in a keystore.properties file next to it. Without it (a fresh clone, a pull request)
// the release build is signed with the debug key so it still installs.
val keystoreFile = providers.gradleProperty("prompter.keystore")
    .orElse(providers.environmentVariable("PROMPTER_KEYSTORE"))
    .orElse(providers.systemProperty("user.home").map { "$it/.prompter/prompter-release.jks" })
    .map { file(it) }
    .orNull
    ?.takeIf { it.isFile }
val keystoreProps = Properties().apply {
    keystoreFile?.resolveSibling("keystore.properties")?.takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun secret(name: String): String? =
    keystoreProps.getProperty(name) ?: providers.environmentVariable("PROMPTER_${name.uppercase()}").orNull

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
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = secret("storePassword")
                keyAlias = secret("keyAlias")
                keyPassword = secret("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No shrinking: the release runs the same code as the debug build that was tested on a Pixel.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
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
