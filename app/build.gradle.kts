plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor build dikirim dari GitHub Actions (-PbuildNumber=...). Dipakai untuk cek update.
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.byhasstudio.askly"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.byhasstudio.askly"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Kunci tetap supaya setiap build bisa menimpa (update) versi yang sudah terpasang.
    signingConfigs {
        create("fixed") {
            storeFile = file("askly.keystore")
            storePassword = "askly123"
            keyAlias = "askly"
            keyPassword = "askly123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
