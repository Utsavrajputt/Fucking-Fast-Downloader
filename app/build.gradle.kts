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
    }

    // Two flavors instead of one do-everything APK:
    //  - lite: no yt-dlp/ffmpeg at all -- everything this app did before
    //          YouTube support existed (FuckingFast/direct/fitgirl/torrent
    //          only). Ships both arm64-v8a and armeabi-v7a (libtorrent4j
    //          has a build for each -- libtorrent4j-android-arm64 and
    //          libtorrent4j-android-arm -- so there's no reason to leave
    //          32-bit devices out here), split into 2 separate APKs.
    //  - full: adds YouTube (yt-dlp) support. The python+ffmpeg binaries
    //          this needs can't be downloaded at runtime on Android 10+
    //          (see core/YtDlpManager.kt in the full/ source set for why),
    //          so this flavor ships them bundled as a separate, larger APK
    //          instead. youtubedl-android supports armeabi-v7a too, so like
    //          lite, this splits into 2 separate per-ABI APKs below rather
    //          than one universal one -- an arm64 device's download never
    //          carries the armeabi-v7a copy of yt-dlp/ffmpeg/python, or
    //          vice versa.
    //
    // ABI selection lives entirely in the `splits { abi { ... } }` block
    // below, NOT in ndk.abiFilters -- AGP rejects having both set for the
    // same ABIs ("Conflicting configuration ... in ndk abiFilters cannot
    // be present when splits abi filters are set"). Since both flavors
    // want the same two ABIs here, one shared splits.abi block is enough.
    flavorDimensions += "engine"
    productFlavors {
        create("lite") {
            dimension = "engine"
            buildConfigField("boolean", "HAS_YOUTUBE_SUPPORT", "false")
        }
        create("full") {
            dimension = "engine"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "HAS_YOUTUBE_SUPPORT", "true")
        }
    }

    // Splits each flavor's two ABIs into separate APKs -- Xmd-lite-arm64-v8a
    // / Xmd-lite-armeabi-v7a / Xmd-full-arm64-v8a / Xmd-full-armeabi-v7a.
    // isUniversalApk = false: no combined-ABI fallback is built, since every
    // real device is one or the other and a universal APK would just mean
    // everyone downloads both ABIs' worth of native libs for nothing.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // Minification/shrinking turned OFF: libtorrent4j and
            // youtubedl-android/ffmpeg both do heavy reflection/JNI-name
            // lookups internally, and R8 keeps finding new classes to
            // strip/rename that break them at runtime (NoClassDefFoundError
            // etc.) even with targeted -keep rules added after each crash.
            // APK size cost is small in context -- the native yt-dlp/ffmpeg/
            // libtorrent4j binaries already dominate the size, not app code
            // -- and "always works" beats "a few MB smaller, breaks
            // unpredictably." Revisit only with real on-device testing.
            isMinifyEnabled = false
            isShrinkResources = false
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
        buildConfig = true
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
    // both native .so variants are declared here -- the splits.abi block
    // above (not ndk.abiFilters, which can't coexist with it) prunes
    // whichever ABI a given output doesn't need.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-38")

    // YouTube (and future yt-dlp-supported sites) downloading -- Android
    // wrapper around the actual yt-dlp + ffmpeg binaries, on Maven Central.
    // "full"-flavor only: see the flavor comment above for why this can't
    // be a runtime download and has to be an opt-in separate APK instead.
    "fullImplementation"("io.github.junkfood02.youtubedl-android:library:0.18.1")
    "fullImplementation"("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
