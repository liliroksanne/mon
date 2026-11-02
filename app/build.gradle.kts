import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.lilyroksanne.mon"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.lilyroksanne.mon"
        minSdk = 27
        targetSdk = 37
        versionCode = 2
        versionName = "2.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val geminiApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: "\"\""
        buildConfigField("String", "GEMINI_API_KEY", geminiApiKey)

        val geminiType = localProperties.getProperty("GEMINI_TYPE") ?: "\"\""
        buildConfigField("String", "GEMINI_TYPE", geminiType)
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

base {
    archivesName.set("Mon-v${android.defaultConfig.versionName}")
}

dependencies {
    // Gemini SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
    // Corrutinas (requeridas para usar delay y llamadas asíncronas)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Google ML Kit (Gemini Nano Local) para IA On-Device
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // DocumentFile para manejar SAF
    implementation("androidx.documentfile:documentfile:1.0.1")
// Room (SQLite)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")



    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}