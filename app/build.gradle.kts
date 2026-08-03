import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Come per Pactum: le chiavi vivono FUORI dal repo e da OneDrive.
// C:\Users\andre\sol-keys\sol.properties → picovoiceAccessKey=...
// Se il file manca l'app compila e funziona lo stesso: la wake word resta
// spenta e si usa il pulsante Parla (push-to-talk).
val chiaviFile = file("C:/Users/andre/sol-keys/sol.properties")
val chiavi = Properties().apply {
    if (chiaviFile.exists()) {
        FileInputStream(chiaviFile).use { load(it) }
    }
}

android {
    namespace = "eu.stgm.sol"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.stgm.sol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${chiavi.getProperty("picovoiceAccessKey") ?: ""}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.porcupine.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Il cancello conferme è l'unico punto dove un bug diventa un danno dal
    // vivo: la sua logica pura (normalizzazione frase, regole di rischio,
    // frasi degli esiti) si prova su JVM, senza telefono.
    testImplementation(libs.junit)
}
