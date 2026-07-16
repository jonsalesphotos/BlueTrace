pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BlueTrace"
include(":shared")
include(":app")

// ============================================================================
// [#25 分层观测 · 临时诊断构建 —— 不得进 main]
// ----------------------------------------------------------------------------
// 用 Nordic beta03 的**只观测 fork**(仅加日志, 零行为改动)替换官方制品, 以取得黑盒拿不到的三层证据:
//   1) DISC_REQUEST  —— gatt.discoverServices() 的返回值(上游丢弃), 判"Android 是否接受请求";
//   2) DISC_CALLBACK —— 原生 onServicesDiscovered 是否到达 + subscriptionCount + tryEmit 返回值
//      (_events 是 MutableSharedFlow(replay=0): 无订阅者时 tryEmit 返回 true 但事件被丢弃,
//       故 "subs=0 & tryEmit=true" 即事件静默丢失的铁证);
//   3) DISC_CONSUMED —— Nordic 的 Peripheral 是否真消费了事件(= 状态离开 Discovering + 释放全局锁).
// fork 源 = 官方 tag `2.0.0-beta03`(git tag --points-at HEAD 确认), 且与 Maven 官方 sources jar
// 逐字比对仅换行符差异 => 观测结果可直接对应线上行为.
// 取证完毕后本段与 fork 一并撤除; 详见 `Docs/设计/Nordic重连挂死_根因分析.md`.
// ============================================================================
val nordicForkDir = file("../_nordic_fork")
if (nordicForkDir.exists() && providers.gradleProperty("bluetrace.nordicFork").orNull == "true") {
    includeBuild(nordicForkDir) {
        dependencySubstitution {
            substitute(module("no.nordicsemi.kotlin.ble:client-android")).using(project(":client-android"))
            substitute(module("no.nordicsemi.kotlin.ble:environment-android")).using(project(":environment-android"))
        }
    }
}
