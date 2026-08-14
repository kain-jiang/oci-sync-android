// 国内镜像(默认开启,加速本机/国内 CI);GitHub Actions 等海外环境设 USE_CN_MIRRORS=false 走官方源
// 注:Gradle 限制 pluginManagement/dependencyResolutionManagement 块内不能引用脚本局部变量,
// 故块内直接调用 System.getenv()

pluginManagement {
    repositories {
        if (System.getenv("USE_CN_MIRRORS") != "false") {
            // 国内镜像优先(阿里云),官方源兜底
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("USE_CN_MIRRORS") != "false") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "oci-sync-android"
include(":app")
include(":core")
