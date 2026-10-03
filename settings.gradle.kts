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
        // Xray-core compiled with gomobile (Go -> AAR).
        maven {
            url = uri("https://raw.githubusercontent.com/homa-games/libXray/repo/repo")
            content { includeGroup("io.github.homa-games") }
        }
    }
}

rootProject.name = "Nodeway VPN"
include(":app")