import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// La chiave di firma vive FUORI dal repo e da OneDrive, la stessa per sempre:
// senza, le app già installate non si aggiornano più. Se il file manca (altra
// macchina) la release resta non firmata ma la build non si rompe.
val keystorePropsFile = file("C:/Users/andre/sol-keys/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

/** storeFile può essere scritto relativo alla cartella delle chiavi. */
fun fileChiave(percorso: String): File {
    val f = File(percorso)
    return if (f.isAbsolute) f else File(keystorePropsFile.parentFile, percorso)
}

android {
    namespace = "eu.stgm.sol"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.stgm.sol"
        minSdk = 26
        targetSdk = 35
        // Nuova versione = nuovo versionCode, altrimenti l'aggiornamento
        // automatico (GET /api/versione) non la propone.
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = fileChiave(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig =
                if (keystorePropsFile.exists()) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Il fotografo (Robolectric) ha bisogno delle risorse vere per disegnare.
        unitTests.isIncludeAndroidResources = true
    }
}

// Il "fotografo": test che disegnano le schermate in PNG sul PC (Robolectric +
// Roborazzi, niente telefono). Lenti, quindi fuori dalla suite normale: partono
// SOLO con -Pfotografo, e allora girano solo loro.
//   gradlew :app:testDebugUnitTest -Pfotografo [-Pfotografo.cartella=C:/percorso]
val fotografo = providers.gradleProperty("fotografo").isPresent
val cartellaFoto = providers.gradleProperty("fotografo.cartella")
tasks.withType<Test>().configureEach {
    if (fotografo) {
        filter { includeTestsMatching("eu.stgm.sol.fotografo.*") }
        systemProperty("roborazzi.test.record", "true")
        systemProperty(
            "fotografo.cartella",
            cartellaFoto.orElse(layout.buildDirectory.dir("fotografo").map { it.asFile.path }).get(),
        )
        maxHeapSize = "3g"
        outputs.upToDateWhen { false }
    } else {
        exclude("eu/stgm/sol/fotografo/**")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Solo debug: l'attività vuota su cui il fotografo disegna le schermate.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
