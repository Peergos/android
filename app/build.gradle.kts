plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "peergos.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "peergos.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(files("libs/Peergos.jar"))
    implementation(files("libs/http-2.2.1.jar"))
    implementation(files("libs/sun-common-server.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}