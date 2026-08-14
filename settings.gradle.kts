// 国内镜像(默认开启,加速本机/国内 CI);GitHub Actions 等海外环境设 USE_CN_MIRRORS=false 走官方源
val useCnMirrors = System.getenv("USE_CN_MIRRORS") != "false"

pluginManagement {
    repositories {
        if (useCnMirrors) {
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
        if (useCnMirrors) {
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
