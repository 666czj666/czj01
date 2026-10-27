pluginManagement {
    repositories {
        // 国内镜像优先：dl.google.com 在本机经常 Read timed out，
        // 尤其是 lint 那批体积较大的包（lint-checks / intellij-core / kotlin-compiler）。
        // 保留官方源作为兜底 —— 镜像缺包时仍能解析。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "zilv"
include(":app")
