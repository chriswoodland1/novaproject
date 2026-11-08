import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Verzió: a GitHub Actions a git tagből adja (pl. v1.2.3 -> 1.2.3), helyben alapérték.
val envVersionName: String = System.getenv("NOVA_VERSION_NAME") ?: "0.1.0"
val envVersionCode: Int = System.getenv("NOVA_VERSION_CODE")?.toIntOrNull() ?: 1
val githubRepo: String = (project.findProperty("NOVA_GITHUB_REPO") as String?) ?: "owner/repo"

android {
    namespace = "hu.novaprocess"
    compileSdk = 35

    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // ONNX Runtime natív könyvtárai: csak telefon-ABI-k
        applicationId = "hu.novaprocess"
        minSdk = 26
        targetSdk = 35
        versionCode = envVersionCode
        versionName = envVersionName
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    // Aláírás: ha a CI-ban van keystore (secrets), azzal; különben debug kulccsal.
    val keystorePath = System.getenv("NOVA_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NOVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOVA_KEY_ALIAS")
                keyPassword = System.getenv("NOVA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null && file(keystorePath).exists())
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api", "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += listOf("onnx") }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.pickFirsts += "META-INF/DEPENDENCIES"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.google.zxing:core:3.5.3")
    // Seestar élő videó (RTSP)
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    // MI-modellek (GraXpert, AstroSleuth) futtatása
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Seestar SMB megosztás böngészése (EMMC Images)
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
}
