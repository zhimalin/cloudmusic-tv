import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cloudmusic.core.player"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // PlaybackQueue / LrcParser / PlaybackUrlResolver 都不碰 Android API，
    // 单测跑在纯 JVM 上，不需要 Robolectric。
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Song / SongLevel / NeteaseClient 出现在 PlaybackUrlResolver、MusicPlayer 的公开签名里。
    api(project(":core-network"))
    // PlaybackRuntime 从 SessionStore / SettingsStore 读取登录态与默认音质。
    implementation(project(":core-data"))
    // StateFlow 出现在 MusicPlayer 的公开 API 里。
    api(libs.kotlinx.coroutines.core)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    testImplementation(libs.junit)
    // 降级链路用 MockWebServer 离线验证，不需要真实网络。
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
