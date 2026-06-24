plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

// SQLDelight 2.3.2（已正式兼容 AGP 9 新 DSL / Built-in Kotlin，见 issue #5940）：
// 从 commonMain/sqldelight 的 .sq 生成类型安全数据库 BlueTraceDb（KMP，iOS 可复用）。
sqldelight {
    databases {
        create("BlueTraceDb") { packageName.set("io.bluetrace.shared.db") }
    }
}

kotlin {
    jvm()

    // AGP 9 KMP android target: com.android.kotlin.multiplatform.library
    android {
        namespace = "io.bluetrace.shared"
        compileSdk = 36
        minSdk = 29
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.ext)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
        // JdbcSqliteDriver 是 JVM-only（非 KMP 元数据可见），故 SQLDelight 仓库测试落 jvmTest。
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
