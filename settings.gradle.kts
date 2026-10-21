rootProject.name = "sellerflow-mcp"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":core",
    ":storage",
    ":allegro",
    ":inpost",
    ":ksef",
    ":invoice",
    ":workflow",
    ":mcp-server",
)
