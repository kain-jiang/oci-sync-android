// app 模块:Android 应用(UI、导航、ViewModel、系统集成)
// 注:AGP 9.0+ 内置 Kotlin 支持,不再需要 org.jetbrains.kotlin.android 插件(见 ADR-011)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tiramission.ocisync"
    // 注:2026-08 最新 androidx 稳定版(core-ktx 1.19.0 等)要求 compileSdk 37+
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tiramission.ocisync"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 注:AGP 9 built-in Kotlin 下无 kotlinOptions 扩展,Kotlin jvmTarget 自动与 compileOptions 对齐
    buildFeatures {
        compose = true
        buildConfig = true // 设置页显示版本号
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric 需要
            all { it.maxHeapSize = "1g" }  // Robolectric 加载 android-all 需要较多内存
        }
    }
}

dependencies {
    implementation(project(":core"))

    // core 的 implementation 依赖不传递,app 直接使用处需显式声明
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // JVM 单元测试(Robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
