// 注意：这里必须显式 import Properties。
// 在 Kotlin DSL 里裸写 `java.util.Properties` 会失败 ——
// `java` 被 Project 的 JavaPluginExtension 遮蔽了，`util` 解析不到。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// 正式签名（PRD M7 T7.7）
//
// 凭据放在 android/keystore.properties（已被 .gitignore 排除）。
//
// ⚠️ **务必备份 keystore 文件**：Android 用签名证书判断"是不是同一个应用"，
// 丢了它就无法对已安装的 App 做覆盖升级，只能卸载重装（数据会丢）。
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.zilv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zilv.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "2.15"
        // 个人自用：只保留真机架构，缩小 APK
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // ⚠️ signingConfigs 必须写在 buildTypes **之前**：
    // buildTypes 里的 signingConfigs.getByName("release") 在配置阶段就会求值，
    // 顺序反了会报 "SigningConfig with name 'release' not found"。
    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            // 没有 keystore.properties 时退化为未签名，方便新机器先跑通编译
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Room 的 schema 导出目录（exportSchema = true 时必需）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// 测试 JVM 配置
//
// 历史记录（避免以后重复踩坑）：曾尝试用 Robolectric 在 JVM 上跑真实 Room 数据库，
// 但本机沙箱下无法工作：
//   - LEGACY SQLite（sqlite4java）：需要把原生 DLL 解压到临时目录 → 被拦截
//   - NATIVE SQLite：挂起
// 因此改为分层验证：
//   - 仓库业务逻辑 → 纯 JVM 单元测试（内存 Fake DAO），见 FakeDaos.kt
//   - Room SQL 与约束 → Room 注解处理器编译期校验 + androidTest 仪器测试
// ---------------------------------------------------------------------------
tasks.withType<Test>().configureEach {
    // 让测试报告中显示完整的异常堆栈
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // 数据与依赖注入
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.work.runtime.ktx)
    implementation(libs.play.services.location)

    debugImplementation(libs.compose.ui.tooling)

    // ---- JVM 单元测试（无需设备）----
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)

    // ---- 仪器测试（需要连接设备/模拟器）----
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.truth)
}
