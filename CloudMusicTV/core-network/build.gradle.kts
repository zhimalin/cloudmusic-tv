import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // weapi 请求体/响应体用 @Serializable 数据类映射，需要 kotlinx-serialization 编译插件。
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cloudmusic.core.network"
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

    // 单测是纯 JVM 测试（core-network 不碰 Android API），不需要 Robolectric。
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // 离线集成测试：假服务器验证请求形态/错误映射，不需要真实网络
    testImplementation(libs.okhttp.mockwebserver)
}

// 真实网络集成测试需要 cookie 路径：gradlew :core-network:testDebugUnitTest -DprobeCookie=<path>
// Gradle 的 -D 只作用在构建 JVM 上，这里显式转发给测试 JVM；也支持 -PprobeCookie / PROBE_COOKIE。
tasks.withType<Test>().configureEach {
    val probeCookie = providers.systemProperty("probeCookie").orNull
        ?: providers.gradleProperty("probeCookie").orNull
        ?: providers.environmentVariable("PROBE_COOKIE").orNull
    if (!probeCookie.isNullOrBlank()) {
        systemProperty("probeCookie", probeCookie)
    }

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
