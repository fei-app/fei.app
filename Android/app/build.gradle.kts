plugins {
    alias(libs.plugins.android.application)
}
android {
    namespace = "com.marinov.openfei"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marinov.openfei"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.5"
        buildConfigField("String", "GITLAB_PAT", "\"${project.findProperty("GITLAB_PAT") ?: ""}\"")
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
}

dependencies {

    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.webkit)
    implementation(libs.core.ktx)
    implementation (libs.jsoup)
    implementation (libs.gson)
    implementation (libs.work.runtime.ktx)
    implementation (libs.glide)
    annotationProcessor (libs.compiler)
    implementation (libs.swiperefreshlayout)
}
