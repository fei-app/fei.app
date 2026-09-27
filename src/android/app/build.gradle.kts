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
        versionCode = 1
        versionName = "10.26092026"
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.webkit)
    implementation(libs.core.ktx)
    implementation (libs.gson)
    implementation (libs.work.runtime.ktx)
    implementation (libs.glide)
    annotationProcessor (libs.compiler)
    implementation (libs.swiperefreshlayout)
    implementation(libs.security.crypto)
    implementation(libs.autostarter)
    implementation(libs.biometric)
}
