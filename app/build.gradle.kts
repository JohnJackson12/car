plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voicemusic.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicemusic.app"
        minSdk = 21              // Android 5.0+ (covers old head units)
        targetSdk = 29           // legacy storage = plain file access on all Android versions
        versionCode = 1
        versionName = "1.0"
    }

    // Two APKs from one code base:
    //  standard = target 29 (normal runtime permission dialogs)
    //  car      = target 22 (permissions are granted at install time; for head-unit ROMs
    //             whose permission pop-ups are broken). Not installable on Android 14+.
    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
        }
        create("car") {
            dimension = "mode"
            applicationIdSuffix = ".car"
            versionNameSuffix = "-car"
            targetSdk = 22
        }
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/voicemusic.jks")
            storePassword = "voicemusic"
            keyAlias = "voicemusic"
            keyPassword = "voicemusic"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Extract native libs to disk: most compatible with old/odd ROMs and with JNA.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    androidResources {
        // The speech model is big and already compressed; don't try to compress twice.
        noCompress += setOf("mdl", "fst", "int", "conf", "txt", "mat", "stats", "dubm", "ie", "raw", "final")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-nowarn", "-Xlint:none"))
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
