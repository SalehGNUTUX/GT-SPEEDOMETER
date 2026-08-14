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

rootProject.name = "GT-SPEEDOMETER"
include(":app")
// واجهة OsmAnd الخارجيّة — شفرةٌ منسوخة من مستودع OsmAnd، معزولةٌ عن شفرتنا
include(":osmand-api")
