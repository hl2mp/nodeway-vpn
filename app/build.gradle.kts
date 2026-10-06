import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

/*
 * Подпись release.
 *
 * Файл keystore.properties и сам ключ в git не попадают: пароль от подписи не
 * должен уезжать в историю. Порядок такой: файл → переменная окружения → ничего.
 * Без ключа release собирается неподписанным, а не падает: иначе на CI, где
 * секретов нет, нельзя было бы собрать APK вообще.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** Значение из файла, иначе из переменной окружения, иначе null. */
fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "NODEWAY_STORE_FILE")

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

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "NODEWAY_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "NODEWAY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "NODEWAY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // null, когда ключа нет: APK выходит неподписанным, сборка не падает.
            signingConfig = signingConfigs.findByName("release")

            optimization {
                // R8 выключен намеренно: ядро ходит в Go через JNI и рефлексию,
                // а про правила сохранения здесь ничего не документировано.
                enable = false
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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

    testOptions {
        // LinkListParser трогает android.util.Base64 при base64-подписках;
        // для текстовых тестов это не нужно.
        unitTests.isReturnDefaultValues = true
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
    // Настройки WebView, недоступные на платформе: отключение принудительной
    // тёмной темы, которой иначе Android 10 перекрашивает нашу страницу сам.
    implementation(libs.androidx.webkit)

    /*
     * Xray-core 26.9.30, собран из XTLS/libXray@v26.9.30 через gomobile bind:
     *   gomobile bind -target android -androidapi 21 \
     *     -ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384"
     * Собранный AAR лежит рядом — от maven-зависимости отказались, чтобы
     * тянуть свежую версию ядра независимо от мейнтейнеров форка.
     */
    implementation(files("libs/libXray.aar"))

    testImplementation(libs.junit)

    /*
     * Настоящий org.json вместо заглушки из android.jar: в обычных JVM-тестах
     * JSONObject.put возвращает null, и любой код, строящий конфиг, падает.
     * С этой зависимостью XrayConfigBuilder удаётся покрыть тестами — а он уже
     * выпустил баг, который молча ломал профили с TLS.
     */
    testImplementation(libs.json)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}