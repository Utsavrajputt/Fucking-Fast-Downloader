plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.invictus.xmd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.invictus.xmd"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.0-beta.1"

        // Only shipping the arm64 (arm64-v8a) native libs -- see the
        // libtorrent4j dependency below, which is arm64-only to match.
        // Cuts APK size vs. bundling all 4 ABIs; means the app won't
        // install on 32-bit-only (armeabi-v7a) or x86/x86_64 devices —
        // fine for real phones today (arm64-v8a has been standard since
        // ~2017), but rules out emulators running an x86_64 image unless
        // that emulator also supports arm64 system images.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No signingConfig here on purpose: this build type produces an
            // unsigned APK (app-release-unsigned.apk). Signing is done
            // explicitly with apksigner in .github/workflows/android-build.yml
            // (or manually for local release testing), keeping build and
            // sign as separate, visible steps.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room: persists the download queue to disk so it survives app/process
    // restart (previously QueueRepository was in-memory only -- see
    // core/db/AppDatabase.kt).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // libtorrent4j: real BitTorrent engine (magnet links + .torrent files) --
    // see core/TorrentEngine.kt. The main artifact is pure-Java bindings;
    // arm64-only native .so to match the ndk.abiFilters restriction above
    // (keeps this from adding an extra native lib per other ABI to the APK).
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
}
