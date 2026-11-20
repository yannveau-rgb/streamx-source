import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = file("${System.getProperty("user.home")}/streamx-keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Clé gratuite The Movie Database (lecture seule), facultative : ~/streamx-keys/tmdb.properties avec une ligne « key=... ».
val localProps = Properties().also { p ->
    val f = file("${System.getProperty("user.home")}/streamx-keys/tmdb.properties")
    if (f.exists()) f.inputStream().use { p.load(it) }
}
val tmdbKey: String = localProps.getProperty("key", "").trim()
// Nom d'un ancien serveur d'images à remplacer par celui de l'abonnement actif (facultatif, même fichier : « deadImageHost=... »).
val deadImageHost: String = localProps.getProperty("deadImageHost", "").trim()

android {
    namespace = "eu.streamx.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "eu.streamx.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 48
        versionName = "0.9.2"
        // URL du fichier latest.json (laisser vide tant que l'hÃ©bergement n'est pas choisi : la vÃ©rification est alors dÃ©sactivÃ©e).
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
        buildConfigField("String", "DEAD_IMAGE_HOST", "\"$deadImageHost\"")
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/yannveau-rgb/streamx-releases/releases/latest/download/latest.json\"")
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("streamx") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Allègement (R8) : code inutilisé retiré, classes raccourcies ; moins de mémoire et démarrage plus rapide sur les clés TV modestes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("streamx")?.let { signingConfig = it }
        }
        debug {
            // MÃªme clÃ© que la version publiÃ©e : on peut passer de l'une Ã  l'autre sans dÃ©sinstaller.
            signingConfigs.findByName("streamx")?.let { signingConfig = it }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Fichiers de la bibliothèque protobuf inutiles à l'exécution (~120 Ko).
    packaging { resources { excludes += listOf("google/protobuf/*.proto", "core/*.proto.bin") } }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // Onglet Tube : lecture de YouTube sans compte ni publicité (même bibliothèque que NewPipe).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}






