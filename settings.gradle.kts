pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ★ Xposed API 官方仓库（实测可达 HTTP 200）。
        //   只作 compileOnly 用：运行时的 de.robv.android.xposed.* 由 LSPosed 框架
        //   在宿主进程里提供，打进 APK 反而会重复类。
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "HyperPlus"
include(":app")
