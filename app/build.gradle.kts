@file:Suppress("UnstableApiUsage")
plugins {
    id("com.android.application")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37
    namespace = "com.xposed.wetypehook"

    /*
     * 2026-08-26
     * Change type: config
     * What: 将 NPatch 内嵌兼容与微信输入法内可见入口版本提升到 1.29.0（versionCode 35）。
     * Why: 与仅支持独立 LSPosed 安装的 1.28.0 明确区分，并允许保留已有模块数据覆盖升级。
     * Params & return: 影响 APK versionCode 与 versionName，无运行时参数或返回值。
     * Impact scope: 构建产物文件名、Android 包管理器升级判断和发布标识。
     * Risk: 无已知风险。
     */
    defaultConfig {
        applicationId = "com.xposed.wetypehook"
        minSdk = 31
        targetSdk = 37
        versionCode = 35
        versionName = "1.29.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += arrayOf("kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "WeType_UI_Enhanced-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation-android:1.11.4")
    implementation("androidx.compose.ui:ui-android:1.11.4")
    implementation("androidx.compose.ui:ui-graphics-android:1.11.4")
    implementation("androidx.compose.ui:ui-text-android:1.11.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-core-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-shapes-android:0.9.0")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.0") {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-android")
    }
    implementation("io.github.kyant0:capsule:2.1.3")
    implementation("org.luckypray:dexkit:2.2.0")
}
