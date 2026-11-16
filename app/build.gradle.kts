plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("app.cash.paparazzi")
}

android {
    namespace = "io.github.redeyelabs.vigilo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.redeyelabs.vigilo"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.1.2"
        // Schema del redirect OAuth: vigilo://oauth/callback
        manifestPlaceholders["oauthScheme"] = "vigilo"
    }

    buildTypes {
        release {
            // Non minificare: R8 aveva già rotto il TLS del proxy, e può toccare
            // serialization/Room/WorkManager/widget. Firma debug per installarla sopra le versioni esistenti.
            // Restano escluse solo le risorse PQC inutili di Bouncy Castle (packaging).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // I JAR firmati di Bouncy Castle portano META-INF/*.SF/*.RSA che in un APK vanno esclusi.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                // Risorse PQC di Bouncy Castle (es. picnic *.properties), non usate dal proxy TLS.
                "org/bouncycastle/pqc/**",
            )
            pickFirsts += setOf("META-INF/services/java.security.Provider")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Scanner QR per l'abbinamento con Home Assistant (modulo di Play Services, nessun permesso fotocamera).
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Play Services porta un Fragment vecchio: serve >= 1.3 per le Activity Result API.
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("org.bouncycastle:bctls-jdk18on:1.80")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
