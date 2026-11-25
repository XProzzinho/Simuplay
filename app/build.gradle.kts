plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geanalves.simulplay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geanalves.simulplay"
        minSdk = 22
        targetSdk = 34
        // o GitHub Actions passa o número do build: cada build novo é uma versão maior
        val build = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    signingConfigs {
        create("geanplay") {
            storeFile = file("release.jks")
            storePassword = "geanplay"
            keyAlias = "geanplay"
            keyPassword = "geanplay"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("geanplay")
        }
        debug {
            signingConfig = signingConfigs.getByName("geanplay")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
}
