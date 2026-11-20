pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // NewPipeExtractor (onglet Tube) n'est publié que sur JitPack, et JitPack ne sert que ce groupe (pas de substitution possible).
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "StreamX"
include(":app")
