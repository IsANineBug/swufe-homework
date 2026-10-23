plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 构建时注入的配置。变量名按 issue #4 裁决 1 与 WS6 release.yml 统一（见 docs/workstreams.md）：
//   SERVER_BASE_URL / SERVER_CERT_PIN → BuildConfig
//   SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD → release 签名
//   VERSION_NAME / VERSION_CODE
// 本地可在 ~/.gradle/gradle.properties 用同名 Gradle 属性覆盖。不要提交真实值。
fun cfg(name: String, default: String = ""): String =
    (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: default).replace("\"", "").trim()

val defaultServerUrl = "https://127.0.0.1:8443/api/v1/"
val serverUrl = cfg("SERVER_BASE_URL", defaultServerUrl)
val certPin = cfg("SERVER_CERT_PIN")
val signingStoreFile = cfg("SIGNING_STORE_FILE")

android {
    namespace = "com.swufe.homework"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swufe.homework"
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("VERSION_CODE", "1").toInt()
        versionName = cfg("VERSION_NAME", "0.1.0")

        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "CERT_PIN", "\"$certPin\"")
        buildConfigField("String", "GITHUB_REPO", "\"IsANineBug/swufe-homework\"")
    }

    signingConfigs {
        create("release") {
            if (signingStoreFile.isNotEmpty()) {
                storeFile = file(signingStoreFile)
                storePassword = cfg("SIGNING_STORE_PASSWORD")
                keyAlias = cfg("SIGNING_KEY_ALIAS")
                keyPassword = cfg("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // 视觉规范截图：./gradlew testDebugUnitTest --tests '*DesignScreenshotTest' -Pscreenshots=<输出目录>
        // 不传时不跑截图测试（Robolectric 原生绘图只支持 x86_64 Linux / macOS / Windows）。
        unitTests.all { test ->
            val dir = project.findProperty("screenshots") as String?
            if (dir != null) test.systemProperty("swufe.screenshots", file(dir).absolutePath)
            else test.exclude("**/DesignScreenshotTest*")
        }
    }
}

// release 必须 fail-closed：缺 pin、地址仍是默认值、或缺签名配置时直接构建失败，
// 不允许产出无证书固定或未签名的 APK。debug 不受影响。
gradle.taskGraph.whenReady {
    val releaseTasks = allTasks.filter { it.project == project && it.name.contains("Release") &&
        (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package")) }
    if (releaseTasks.isNotEmpty()) {
        val missing = buildList {
            if (certPin.isEmpty()) add("SERVER_CERT_PIN")
            if (serverUrl == defaultServerUrl || !serverUrl.startsWith("https://")) add("SERVER_BASE_URL（须为 https 且不是默认 127.0.0.1）")
            if (signingStoreFile.isEmpty() || !file(signingStoreFile).exists()) add("SIGNING_STORE_FILE")
            if (cfg("SIGNING_STORE_PASSWORD").isEmpty()) add("SIGNING_STORE_PASSWORD")
            if (cfg("SIGNING_KEY_ALIAS").isEmpty()) add("SIGNING_KEY_ALIAS")
            if (cfg("SIGNING_KEY_PASSWORD").isEmpty()) add("SIGNING_KEY_PASSWORD")
        }
        if (missing.isNotEmpty()) throw GradleException("release 构建缺少配置：${missing.joinToString("、")}")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process) // 后台识图：判断 App 前后台
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
