import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Release signing.
//
// Credentials live in the project-root `keystore.properties` (gitignored), never
// in this script. The keystore file itself is outside the project tree.
// If the properties file is absent, fall back to the debug key so
// `assembleRelease` still works (with a visible warning); an unfinished /
// invalid properties file fails early with a clear message.
// ---------------------------------------------------------------------------
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.isFile

if (!hasReleaseSigning) {
    logger.warn(
        "keystore.properties not found under ${rootProject.projectDir}; " +
            "release build will be signed with the DEBUG key",
    )
}

fun signingProperty(name: String): String =
    keystoreProperties.getProperty(name)
        ?: throw GradleException("keystore.properties is missing required key '$name'")

android {
    namespace = "com.cloudmusic.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudmusic.tv"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.2"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                val store = rootProject.file(signingProperty("storeFile"))
                if (!store.isFile) {
                    throw GradleException(
                        "Release keystore not found at ${store.absolutePath} " +
                            "(resolved from keystore.properties storeFile=${signingProperty("storeFile")})",
                    )
                }
                storeFile = store
                storePassword = signingProperty("storePassword")
                keyAlias = signingProperty("keyAlias")
                keyPassword = signingProperty("keyPassword")
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
            // 仅当 keystore.properties 存在时才有 release 配置；否则退回 debug 签名。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Needed for BuildConfig.VERSION_NAME on the settings "about" row.
        buildConfig = true
    }

    // 播放页的纯 Kotlin 逻辑（歌词行号 / 时间格式 / 进度步进）在 JVM 上跑单测。
    //
    // 但 MainActivity 启动测试要跑在 Robolectric 里：Activity 需要应用的
    // AndroidManifest（theme / launcher activity）+ 资源（Theme.CloudMusicTV），
    // 所以必须打开 isIncludeAndroidResources。这个开关只影响
    // `testDebugUnitTest` / `testReleaseUnitTest` 的 classpath，不进 APK，也不影响 assemble*。
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-network"))
    implementation(project(":core-data"))
    implementation(project(":core-player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    // 封面图；ImageLoader 在 App 层限制内存/磁盘缓存与请求尺寸。
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    // 以下两个只在 testDebugUnitTest / testReleaseUnitTest 的 classpath 上，
    // 不会进入 APK，也没有改变 :app 的 implementation 依赖。
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
