import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "edu.unt.smartsight.metaglassestest"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "edu.unt.smartsight.metaglassestest"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Developer Mode does not attest the app. The Wearables guide says to omit these
        // credentials or set them to 0. This project has no Wearables Developer Center app id.
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"

        // Keys stay in local.properties (gitignored): OPENROUTER_API_KEY and optional OPENROUTER_MODEL.
        val localProperties = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { localProperties.load(it) }
        }
        val openRouterKey = localProperties.getProperty("OPENROUTER_API_KEY").orEmpty()
        val openRouterModel = localProperties.getProperty("OPENROUTER_MODEL")
            ?.takeIf { it.isNotBlank() }
            ?: "google/gemini-2.5-flash"
        buildConfigField("String", "OPENROUTER_API_KEY", openRouterKey.asBuildConfigString())
        buildConfigField("String", "OPENROUTER_MODEL", openRouterModel.asBuildConfigString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.speech)
    implementation(libs.mwdat.inputs)
    implementation(libs.tensorflow.lite)
    testImplementation("junit:junit:4.13.2")
}

private fun String.asBuildConfigString(): String {
    val escaped = buildString {
        append('"')
        for (char in this@asBuildConfigString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append('"')
    }
    return escaped
}
