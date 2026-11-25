plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.marinov.openfei"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.marinov.openfei"
        minSdk = 24
        targetSdk = 37
        versionCode = 20261124
        versionName = "11.20261124"

        // ★ Mantém só os idiomas realmente usados (o app é pt-BR; "en" é o
        // fallback das libs). Corta dezenas de traduções inúteis do APK.
        resourceConfigurations += listOf("pt", "pt-rBR", "en")
    }

    buildTypes {
        release {
            // ★ OTIMIZAÇÃO DEX: R8 remove código morto, faz merge de classes,
            // otimiza e ofusca. Junto com as regras do proguard-rules.pro,
            // derruba drasticamente o nº de métodos/strings no DEX.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Necessário: java.time (CalendarSyncManager) com minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        // ★ OTIMIZAÇÃO DEX: TODO o app usa findViewById — nenhuma classe
        // *Binding é usada. Com viewBinding ligado, ~40 classes geradas
        // iam para o DEX à toa. (Se algum dia usar binding, reative.)
        viewBinding = false
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            // Metadados inúteis em runtime — fora do APK/AAB.
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/com.android.tools/**",
                "META-INF/proguard/**"
            )
        }
        jniLibs {
            // ★ .so NUNCA comprimidas no APK: carregamento direto do APK
            // (menos espaço em disco) + alinhamento de 16 KB exigido pela Play.
            useLegacyPackaging = false
        }
    }

    bundle {
        // Splits por dispositivo no AAB (já são o padrão; explícito p/ clareza).
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.webkit)
    implementation(libs.core.ktx)
    implementation(libs.gson)
    implementation(libs.work.runtime.ktx)
    implementation(libs.glide)
    annotationProcessor(libs.compiler)
    implementation(libs.swiperefreshlayout)
    implementation(libs.security.crypto)
    implementation(libs.biometric)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}