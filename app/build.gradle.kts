plugins {
    id("com.android.application")
}

tasks.register("prepareKotlinBuildScriptModel")

dependencies {
    // libbox.aar = core sing-box (SOCKS/TUN/DNS saja). Tidak ada dependency lain.
    implementation(files("libs/libbox.aar"))
}

android {
    namespace = "id.jhopanstore.pansocks"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.jhopanstore.pansocks"
        minSdk = 24
        targetSdk = 35
        // Fitur baru -> naikkan versi normal (1.1.0).
        // Build ulang tanpa fitur baru -> tambah segmen keempat (1.1.0.1, ...).
        // versionCode selalu naik: major*10000 + minor*100 + patch*10 + build.
        versionCode = 10000
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // libbox.so dimuat lewat System.loadLibrary dari AAR, dan APK universal
    // berisi dua ABI sekaligus: simpan .so tidak terkompresi di dalam APK
    // (useLegacyPackaging) supaya tidak perlu ekstrak ke data dir saat start.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    // APK splits: arm64-v8a, armeabi-v7a, dan satu universal.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // APK rilis ditandatangani kunci rilis, bukan kunci debug. Kunci debug
    // bersifat publik, jadi APK yang ditandatangani dengannya tidak bisa
    // di-update setelah pindah ke kunci rilis (harus uninstall dulu).
    // Keystore diambil dari KEYSTORE_FILE (CI mendekode secret). Kalau tidak
    // ada, build lokal jatuh kembali ke kunci debug supaya tetap bisa dikompilasi.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }
    val hasReleaseKey = releaseKeystore?.exists() == true

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // UI dibangun di Java dengan teks inline (satu bahasa: Indonesia), jadi
        // SetTextI18n tidak relevan di sini. Splash dibuat manual tanpa
        // androidx.core-splashscreen karena proyek ini zero-dependency.
        disable += setOf("SetTextI18n", "CustomSplashScreen")
        warningsAsErrors = false
        abortOnError = true
    }
}
