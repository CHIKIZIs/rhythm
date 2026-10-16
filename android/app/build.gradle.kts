import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key lives in the repo so every build (here or on GitHub) can update the installed app.
val signing = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.chikizis.ritm"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.chikizis.ritm"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.3.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile", "ritm.keystore"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("wav", "woff2") }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
