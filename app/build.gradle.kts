import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keystore dedicato (fuori da git). Stessa firma per debug e release:
// cosi' ogni APK nuovo si installa SOPRA il precedente, senza disinstallare.
// Optional signing key, kept out of git (see README). Without it, debug uses the standard Android debug key
// and release is built unsigned.
val signingFile = rootProject.file(".signing/keystore.properties")
val signingProps: Properties? = if (signingFile.exists()) Properties().apply { signingFile.inputStream().use { load(it) } } else null

android {
    namespace = "com.the151universe.storage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.the151universe.storage"
        minSdk = 26
        targetSdk = 36
        versionCode = 93
        versionName = "0.10.52"
    }
    buildFeatures { buildConfig = true }
    // le prove sul PC usano un Context finto (cartella temporanea): i metodi Android non toccati rispondono «vuoto»
    testOptions { unitTests.isReturnDefaultValues = true }

    signingConfigs {
        if (signingProps != null) create("storage") {
            storeFile = rootProject.file(".signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (signingProps != null) signingConfig = signingConfigs.getByName("storage")
        }
        release {
            if (signingProps != null) signingConfig = signingConfigs.getByName("storage")
            // 11/11: la release si consegna (non più la debug, 3 volte più lenta sul telefono di prova: 1,17 s → 0,39 s
            // all'apertura). Senza R8: mai provato su JSch/OkHttp, una classe tolta romperebbe il cloud in silenzio.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // i .wasm/.bcmap/.pfb sono gia' compressi o piccoli; non serve altro.
        noCompress += listOf("wasm")
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "kotlin/**", "DebugProbesKt.bin", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    dependenciesInfo {
        // niente blob "dependency metadata" firmato da Google nell'APK
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.13.0")
    // 151 Storage: il cloud (WebDAV) e la galleria a pagine
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0") // tirare in giù = aggiorna (Docs, Cloud)
    // un cloud vero con la chiave SSH (SFTP), come il PC e un altro apparecchio
    implementation("com.github.mwiede:jsch:0.2.21")
    // prove sul PC dei freni della batteria (Freni.kt): solo per `./gradlew testDebugUnitTest`, non nell'APK
    testImplementation("junit:junit:4.13.2")
    // le prove di Index e CrashLog leggono JSON veri sul PC (l'android.jar delle prove ha solo gusci vuoti)
    testImplementation("org.json:json:20231013")
}
