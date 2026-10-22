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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HomeBuddy"
include(":app")
include(":core:network")
include(":core:auth")
include(":core:testing")
include(":core:designsystem")
include(":core:data")
include(":feature:auth")
include(":feature:home")
include(":feature:documents")
