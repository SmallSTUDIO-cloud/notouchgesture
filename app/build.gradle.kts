import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gestureModelUrl = "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"
val gestureModelFile = file("src/main/assets/gesture_recognizer.task")

tasks.register("downloadGestureModel") {
    outputs.file(gestureModelFile)
    doLast {
        if (gestureModelFile.exists() && gestureModelFile.length() > 1_000_000L) return@doLast
        gestureModelFile.parentFile.mkdirs()
        val connection = URL(gestureModelUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.requestMethod = "GET"
        connection.connect()
        if (connection.responseCode !in 200..299) {
            error("Could not download MediaPipe gesture model: HTTP ${connection.responseCode}")
        }
        connection.inputStream.use { input ->
            gestureModelFile.outputStream().use { output -> input.copyTo(output) }
        }
        connection.disconnect()
    }
}

tasks.named("preBuild").configure { dependsOn("downloadGestureModel") }

android {
    namespace = "com.samin.notouchgesture"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.samin.notouchgesture"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    implementation("com.google.mediapipe:tasks-vision:1.0.0")
    implementation("com.google.android.gms:play-services-nearby:19.5.0")

    testImplementation("junit:junit:4.13.2")
}
