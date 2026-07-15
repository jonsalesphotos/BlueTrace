plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.bluetrace"
    // Nordic Kotlin-BLE 2.0.0-beta03 的 AAR 元数据硬要求 compileSdk>=37（仅编译期 API 级别，
    // 与 targetSdk/minSdk 运行时行为无关）。targetSdk 保持 36、minSdk 保持 29 不变。
    compileSdk = 37

    defaultConfig {
        applicationId = "io.bluetrace"
        // D-7 / D-V4-19: minSdk 29 (Android 10); compile/target 36 跟进发布。
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Nordic Kotlin-BLE 2.0.0-beta03 用 Kotlin 2.4.0 编译（其 class + 传递的 kotlin-stdlib 元数据版本 2.4.0）;
// 本工程编译器 2.2.10 只能读到 2.3.0。跳过元数据版本校验以消费该库（仅 app 模块, 不动 shared 工具链）。
// W1.6 后若 Nordic 转默认, 应把工程 Kotlin 升到 >=2.4 正式对齐, 届时删除此 flag。
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // Nordic Kotlin-BLE-Library 2.0（BleClient 第二实现 NordicBleClient；DEBUG 三向开关切换，W1.6 真机 A/B 前）。
    implementation(libs.nordic.ble.client.android)
    implementation(libs.nordic.ble.environment.android)

    implementation(libs.sqldelight.android.driver)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
