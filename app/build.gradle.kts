plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "peergos.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "peergos.android"
        minSdk = 30
        targetSdk = 35
        versionCode = 20
        versionName = "1.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    val work_version = "2.10.1"
    val lifecycle_version = "2.9.0"

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(files("libs/Peergos.jar"))
    implementation(files("libs/http-2.2.1.jar"))
    implementation(files("libs/sun-common-server.jar"))
    implementation(libs.exifinterface)
    implementation("androidx.lifecycle:lifecycle-process:$lifecycle_version")
    implementation("androidx.work:work-runtime:$work_version")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}