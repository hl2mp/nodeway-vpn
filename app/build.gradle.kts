import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.nodewayvpn.pro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nodewayvpn.pro"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // gomobile-produced .so files must be stored uncompressed & page-aligned.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    /*
     * Xray-core 26.9.30, собран из XTLS/libXray@v26.9.30 через gomobile bind:
     *   gomobile bind -target android -androidapi 21 \
     *     -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384"
     * Собранный AAR лежит рядом — от maven-зависимости отказались, чтобы
     * тянуть свежую версию ядра независимо от мейнтейнеров форка.
     */
    implementation(files("libs/libXray.aar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}