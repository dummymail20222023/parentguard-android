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
    }
}

rootProject.name = "ParentGuard"

include(
    ":core:core-protocol",
    ":core:core-crypto",
    ":core:core-network",
    ":agent:agent-domain",
    ":agent:agent-data",
    ":agent:agent-collectors",
    ":agent:agent-telecom-optin",
    ":agent:agent-sync",
    ":agent:app-agent",
    ":monitor:monitor-domain",
    ":monitor:app-monitor"
)
