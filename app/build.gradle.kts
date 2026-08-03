import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Come per Pactum: le chiavi vivono FUORI dal repo e da OneDrive.
// C:\Users\andre\sol-keys\sol.properties →
//   picovoiceAccessKey=...   (wake word; senza → push-to-talk)
//   anthropicApiKey=...      (Claude; senza → CervelloEco, l'app ripete e basta)
// Se il file manca l'app compila e funziona lo stesso, con meno funzioni.
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
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${chiavi.getProperty("picovoiceAccessKey") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "ANTHROPIC_API_KEY",
            "\"${chiavi.getProperty("anthropicApiKey") ?: ""}\"",
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
    implementation(libs.okhttp)
    implementation(libs.porcupine.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Logica pura su JVM: cancello, esecutore, date, archivio, corpo delle
    // richieste a Claude. org.json vero in test (quello di android.jar è
    // uno stub che tira eccezioni).
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
